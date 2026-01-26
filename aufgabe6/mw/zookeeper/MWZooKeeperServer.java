package mw.zookeeper;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.zab.MultiZab;
import org.apache.zookeeper.zab.ZabCallback;
import org.apache.zookeeper.zab.ZabStatus;

// TODO: refactor into separate Zab classes
// TODO: remove the whole delay test nonsense
public class MWZooKeeperServer implements ZabCallback {

	private static final Logger logger = LogManager.getLogger(MWZooKeeperServer.class);

	private final MWZooKeeperImpl impl;
    // consider hashing lol
	private final AtomicLong nextZXID = new AtomicLong(1);
	private final MWZooKeeperIdGenerator clientIdGenerator;
	private ServerSocket serverSocket;
	private volatile boolean running = false;
	private Thread acceptThread;
	private ExecutorService workerPool;

	private final MultiZab zab;
	private volatile ZabStatus currentStatus = ZabStatus.LOOKING; // On init leader still undetermined


	// Helper method to deserialize objects from Zab Txn
	private static Serializable deserializeFromBytes(byte[] data) throws IOException, ClassNotFoundException {
		ByteArrayInputStream bais = new ByteArrayInputStream(data);
		ObjectInputStream ois = new ObjectInputStream(bais);
		return (Serializable) ois.readObject();
	}


	public MWZooKeeperServer(MWZooKeeperImpl impl, Properties zabProperties) throws IOException {
		this.impl = impl;
		this.zab = new MultiZab(zabProperties, this);
		this.clientIdGenerator = new MWZooKeeperIdGenerator(zabProperties.getProperty("myid"));
	}

	// ZabCallback interface implementation
	@Override
	public void deliverRequest(Serializable request) {
		// Called concurrently on followers when they receive a write request
		// Followers must forward write requests to the leader
		if (currentStatus == ZabStatus.LEADING) {
			// Leader processes the request
			logger.debug("Leader processing write request: {}", request);
			processWriteRequestAsLeader(request);
		} else if (currentStatus == ZabStatus.FOLLOWING) {
			// Follower forwards to leader via Zab
			logger.debug("Follower forwarding request to leader: {}", request);
			zab.forwardRequest(request);
		}
		// If LOOKING, drop request or queue it
	}

	@Override
	public void deliverTxn(Serializable txn, long zxid) {
		// Called when a transaction has been committed by majority
		// Apply to local state machine
		logger.debug("Delivering transaction with zxid: {}", zxid);

		// Zab passes back the same object we gave to proposeTxn()
		if (!(txn instanceof MWZooKeeperTxn)) {
			logger.error("Received non-MWZooKeeperTxn object: {}", txn);
			return;
		}

		MWZooKeeperTxn zkTxn = (MWZooKeeperTxn) txn;
		impl.applyTxn(zkTxn, zxid);
		logger.debug("Transaction {} applied successfully", zxid);
	}

	@Override
	public void status(ZabStatus status, String leader) {
		logger.info("Zab status changed: {}, leader: {}", status, leader);
		this.currentStatus = status;

		// On becoming leader, process any queued requests
		if (status == ZabStatus.LEADING) {
			logger.info("I am now the LEADER");
		} else if (status == ZabStatus.FOLLOWING) {
			logger.info("I am now a FOLLOWER of {}", leader);
		}
	}

	// Process write request as leader
	private void processWriteRequestAsLeader(Serializable request) {
		if (!(request instanceof MWZooKeeperRequest)) {
			logger.error("Received non-MWZooKeeperRequest: {}", request);
			return;
		}

		MWZooKeeperRequest zkRequest = (MWZooKeeperRequest) request;
		long zxid = nextZXID.getAndIncrement();
		logger.debug("Processing write request as leader with zxid: {}", zxid);

		// Create transaction and propose via Zab
		MWZooKeeperTxn txn = impl.processWriteRequest(zkRequest, zxid);

		// Propose transaction directly - Zab will handle wrapping it internally
		zab.proposeTxn(txn, zxid);
		logger.debug("Transaction with zxid {} proposed to Zab", zxid);
	}

	public void start(int port) throws IOException {
		if (running) {
			logger.warn("Server already running on port {}", serverSocket.getLocalPort());
			return;
		}

		// Start Zab protocol first (this will start leader election)
		logger.info("Starting Zab protocol...");
		zab.startup();
		logger.info("Zab protocol started, leader election in progress");

		// Then start the client-facing server
		serverSocket = new ServerSocket(port);
		running = true;
		ThreadFactory threadFactory = r -> {
			Thread t = new Thread(r, "MWZooKeeper-Worker");
			t.setDaemon(true);
			return t;
		};
		workerPool = Executors.newCachedThreadPool(threadFactory);
		acceptThread = new Thread(this::acceptLoop, "MWZooKeeper-AcceptThread");
		acceptThread.setDaemon(true);
		acceptThread.start();
		logger.info("Server started on port {}", port);
	}

	public void stop() throws IOException {
		logger.info("Stopping server...");
		running = false;
		if (serverSocket != null) serverSocket.close();
		if (acceptThread != null) {
			try { acceptThread.join(1000); } catch (InterruptedException ignored) {}
		}
		if (workerPool != null) {
			workerPool.shutdownNow();
		}
		if (zab != null) {
			logger.info("Shutting down Zab protocol...");
			zab.shutdown();
		}
		logger.info("Server stopped");
	}

	private void acceptLoop() {
		logger.debug("Accept loop started");
		while (running) {
			try {
				Socket client = serverSocket.accept();
                logger.debug("Accepted new client connection from {}", client.getRemoteSocketAddress());
				if (workerPool != null) {
					workerPool.execute(() -> handleClient(client));
				} else {
					// Fallback to spawning a thread if pool was not initialized
					Thread worker = new Thread(() -> handleClient(client), "MWZooKeeper-Worker-Fallback");
					worker.setDaemon(true);
					worker.start();
				}
			} catch (SocketException se) {
				// Socket closed during shutdown
				logger.debug("Accept loop terminated (socket closed)");
				break;
			} catch (IOException e) {
				logger.error("Error accepting connection", e);
			}
		}
		logger.debug("Accept loop ended");
	}

	private void handleClient(Socket socket) {
		logger.debug("Handling client from {}", socket.getRemoteSocketAddress());
		String clientId = clientIdGenerator.nextUniqueId();
		boolean ephemeralNodesCreated = false;

		try {
			socket.setTcpNoDelay(true);
			ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
			out.flush();
			ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
			int responsesSinceReset = 0;

			while (true) {
				Object obj;
				try {
					obj = in.readObject();
				} catch (EOFException eof) {
					// Client closed connection
					logger.debug("Client closed connection: {}", socket.getRemoteSocketAddress());
					break;
				}
				if (!(obj instanceof MWZooKeeperRequest)) {
					// Unknown object; ignore and continue
					logger.warn("Received unknown object type from client: {}", obj.getClass());
					continue;
				}
				MWZooKeeperRequest request = (MWZooKeeperRequest) obj;
				request.setClientId(clientId);
				logger.debug("Received request: {} from {}", request.getOperation(), socket.getRemoteSocketAddress());
				MWZooKeeperResponse response;
				try {
					switch (request.getOperation()) {
					case GET_DATA:
						// Reads are served locally (may return stale data)
						logger.debug("Processing GET_DATA request for path: {}", request.getPath());
						response = impl.processReadRequest(request);
						break;
					case CREATE:
						if (request.getEphemeral()) {
							// Note: We do not know yet for sure if this node will be created successfully
							// therefore, we tread this following variable as
							// "there might be ephemeral nodes for this client"

							ephemeralNodesCreated = true;
						}
					case DELETE:
					case CLEANUP:
					case SET_DATA:
						logger.debug("Processing {} request for path: {}", request.getOperation(), request.getPath());
						response = handleWriteRequestWithZab(request);
						break;
					default:
						logger.warn("Unknown operation: {}", request.getOperation());
						response = new MWZooKeeperResponse();
						response.setException(new MWZooKeeperException("Unknown operation"));
					}
				} catch (Exception e) {
					logger.error("Error processing request", e);
					response = new MWZooKeeperResponse();
					response.setException(new MWZooKeeperException(e.toString()));
				}
				try {
					out.writeObject(response);
					out.flush();
					// Periodically clear the stream's handle table to avoid growth on long-lived connections
					if (++responsesSinceReset % 100 == 0) {
						out.reset();
					}
					logger.debug("Response sent to client");
				} catch (IOException ioe) {
					// Broken pipe / client disconnected
					logger.debug("Failed to send response to client (broken pipe)", ioe);
					break;
				}
			}

			try { in.close(); } catch (IOException ignored) {}
			try { out.close(); } catch (IOException ignored) {}
			socket.close();
			logger.debug("Client handler finished for {}", socket.getRemoteSocketAddress());
		} catch (SocketException se) {
			// Expected when client disconnects
			logger.debug("Client disconnected: {}", se.getMessage());
			try { socket.close(); } catch (IOException ignored) {}
		} catch (IOException | ClassNotFoundException e) {
			logger.error("Client handler error", e);
			try { socket.close(); } catch (IOException ignored) {}
		} finally {
			// client disconnected
			if (ephemeralNodesCreated) {
				// there might exist ephemeral nodes for this client
				// send a cleanup request
				MWZooKeeperRequest request = new MWZooKeeperRequest(MWZooKeeperOperation.CLEANUP, "");
				request.setClientId(clientId);

				try {
					MWZooKeeperResponse response = handleWriteRequestWithZab(request);
					response.getPath();
				} catch(Exception e) {
					System.err.println("Warning: Could not clean up ephemeral nodes after client disconnect: " + e.getMessage());
				}
			}
		}
	}

	private MWZooKeeperResponse handleWriteRequestWithZab(MWZooKeeperRequest request) {
		if (currentStatus == ZabStatus.LEADING) {
			// Leader: process directly and return immediately
			logger.debug("Handling write request as leader");
			long zxid = nextZXID.getAndIncrement();

			// Create transaction and propose via Zab
			MWZooKeeperTxn txn = impl.processWriteRequest(request, zxid);

			// Check if transaction has an error
			if (txn.getException() != null) {
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(txn.getException());
				return errorResp;
			}

			// Propose transaction directly - Zab will handle wrapping it internally
			zab.proposeTxn(txn, zxid);
			logger.debug("Transaction with zxid {} proposed", zxid);

			// Return success with path and stat immediately (fire-and-forget)
			MWZooKeeperResponse resp = new MWZooKeeperResponse();
			resp.setPath(request.getPath());
			// For SET_DATA, include the new version in the response
			if (request.getOperation() == MWZooKeeperOperation.SET_DATA) {
				MWZooKeeperStat stat = new MWZooKeeperStat(txn.getVersion(), System.currentTimeMillis(), zxid);
				resp.setStat(stat);
			}
			return resp;

		} else if (currentStatus == ZabStatus.FOLLOWING) {
			// Follower: forward to leader via Zab and return immediately
			logger.debug("Forwarding write request to leader as follower");
			zab.forwardRequest(request);
			logger.debug("Request forwarded to leader");

			// Return success with path immediately (fire-and-forget)
			// Note: We don't know the zxid or final version since leader will assign it
			MWZooKeeperResponse resp = new MWZooKeeperResponse();
			resp.setPath(request.getPath());
			// For SET_DATA on follower, we can't predict the exact stat
			// Return a dummy stat - this is a limitation of fire-and-forget
			if (request.getOperation() == MWZooKeeperOperation.SET_DATA) {
				MWZooKeeperStat stat = new MWZooKeeperStat(0, System.currentTimeMillis(), 0);
				resp.setStat(stat);
			}
			return resp;

		} else {
			// LOOKING state - cannot process writes during election
			logger.warn("Received write request while in LOOKING state");
			MWZooKeeperResponse resp = new MWZooKeeperResponse();
			resp.setException(new MWZooKeeperException("Cluster is in election - try again later"));
			return resp;
		}
	}


	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.out.println("Usage: MWZooKeeperServer <port> <myid> <peer1,peer2,...>");
			System.out.println("  Example single-node: MWZooKeeperServer 2181 1 localhost:2888:3888");
			System.out.println("  Example multi-node:  MWZooKeeperServer 2181 1 localhost:2888:3888,localhost:2889:3889,localhost:2890:3890");
			System.exit(1);
		}

		int port = Integer.parseInt(args[0]);
		String myid = args[1];
		String peersStr = args[2];

		// Build Zab properties
		Properties zabProperties = new Properties();
		zabProperties.setProperty("myid", myid);

		String[] peers = peersStr.split(",");
		for (int i = 0; i < peers.length; i++) {
			zabProperties.setProperty("peer" + (i + 1), peers[i]);
		}

		MWZooKeeperImpl impl = new MWZooKeeperImpl();
		MWZooKeeperServer server = new MWZooKeeperServer(impl, zabProperties);

		logger.info("Starting MWZooKeeperServer on port {} with ID {}", port, myid);
		logger.info("Peers: {}", peersStr);

		server.start(port);
		logger.info("Server started successfully on port {}", port);
		Thread.currentThread().join();
	}

}
