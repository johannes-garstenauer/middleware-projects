package mw.hybridcloud;

import org.glassfish.jersey.client.ClientProperties;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.*;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

public class MWTweetProvider {

	private static final int MW_DEFAULT_TWEET_BATCH_SIZE = 100;
	private static final int MW_DEFAULT_NUM_SENDER_THREADS = 4;

	private static final int TARGET_REFRESH_DELAY_IN_MS = 5*1000;

	class MWWorkerThread extends Thread {
		private final WebTarget target;
		private final Random random = new Random();

		public MWWorkerThread(WebTarget target) {
			this.target = target;
		}

		@Override
		public void run() {
			while (!Thread.currentThread().isInterrupted()) {
				List<MWTweet> tweets = reader.getNextTweetsBatch(MW_DEFAULT_TWEET_BATCH_SIZE);
				if (tweets == null) {
					System.out.println("Failed to read tweets!");
					System.exit(1);
				}
				sendTweets(tweets);
			}
		}

		private void sendTweets(List<MWTweet> tweets) {
			int counter = 0;
			while(!Thread.currentThread().isInterrupted()) {
				// Send tweets via proxy
				try {
					Response r = target.path("process").request().post(Entity.json(tweets));
					r.close();
					switch (Response.Status.fromStatusCode(r.getStatus())) {
					case OK:
						return;
					case TOO_MANY_REQUESTS:
						// retry later
						break;
					default:
						throw new WebApplicationException("error " + r.getStatus());
					}
				} catch (WebApplicationException|ProcessingException e) {
					String path = target.getUri().toString();
					if (e.getCause() instanceof ConnectException) {
						System.err.println("Failed to connect to " + path);
					} else if (e.getCause() instanceof SocketTimeoutException) {
						System.err.println("Connection timed out to " + path);
					} else {
						System.err.println("Exception during connection to " + path);
						e.printStackTrace();
					}
				}
				try {
					// slightly inspired by https://aws.amazon.com/de/blogs/architecture/exponential-backoff-and-jitter/
					// randomize delay to avoid "thundering herd problem"
					int maxBackoffDelay = 200 * (counter + 1);
					counter++;
					//noinspection BusyWait
					Thread.sleep(random.nextInt(maxBackoffDelay));
				} catch (InterruptedException ignore) {
					// convert InterruptedException to interrupt flag
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private final MWTweetReader reader;
	private final MWTweetTargetSource targetSource;
	private final int threadsPerTarget;

	private final Map<String, List<MWWorkerThread>> workers = new HashMap<>();


	public MWTweetProvider(String tweetsFile, int threadsPerTarget) {
		reader = new MWTweetReader(tweetsFile);
		targetSource = new MWTweetTargetSource();

		this.threadsPerTarget = threadsPerTarget;
	}

	public void start() {
		refreshTargets();

		TimerTask refreshTask = new TimerTask() {
			@Override
			public void run() {
				refreshTargets();
			}
		};
		new Timer().schedule(refreshTask, TARGET_REFRESH_DELAY_IN_MS, TARGET_REFRESH_DELAY_IN_MS);
	}

	private void refreshTargets() {
		List<String> tweetTargets = targetSource.queryTweetTargets();
		if (tweetTargets == null) {
			// Something went wrong, keep old targets for now ...
			return;
		}

		for (String tweetTarget : tweetTargets) {
			if (!workers.containsKey(tweetTarget)) {
				ArrayList<MWWorkerThread> threads = new ArrayList<>();
				workers.put(tweetTarget, threads);
				for (int i = 0; i < threadsPerTarget; i++) {
					threads.add(spawnWorker(tweetTarget));
				}
			}
		}

		Iterator<Map.Entry<String, List<MWWorkerThread>>> it = workers.entrySet().iterator();
		while(it.hasNext()) {
			Map.Entry<String, List<MWWorkerThread>> workerGroup = it.next();
			if (!tweetTargets.contains(workerGroup.getKey())) {
				for (MWWorkerThread thread: workerGroup.getValue()) {
					thread.interrupt();
				}
				it.remove();
			}
		}
	}

	private MWWorkerThread spawnWorker(String targetStr) {
		WebTarget target = ClientBuilder.newClient().target(targetStr);
		// short connection timeout to avoid stalls during shutdown
		target.property(ClientProperties.CONNECT_TIMEOUT, 1000);

		MWWorkerThread workerThread = new MWWorkerThread(target);
		workerThread.start();

		return workerThread;
	}

	public static void main(String [] args) {
		if (args.length < 1) {
			System.out.println("usage: " + MWTweetProvider.class + " <tweet_file> [<threads_per_target>]");
			System.exit(1); 
		}
		
		String tweetsFile = args[0];
		int numThreads = MW_DEFAULT_NUM_SENDER_THREADS;
		
		if (args.length >= 2) {
			numThreads = Integer.parseInt(args[1]);
			if (numThreads <= 0)
				throw new IllegalArgumentException("Invalid thread number");
		}
		System.out.println("Using " + numThreads + " threads per target");

		MWTweetProvider provider = new MWTweetProvider(tweetsFile, numThreads);
		provider.start();
	}
	
}
