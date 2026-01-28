package mw.zookeeper;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.zab.MultiZab;
import org.apache.zookeeper.zab.ZabCallback;
import org.apache.zookeeper.zab.ZabStatus;

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

	// Track pending synchronous writes
	// Leader uses zxid to track requests it originated
	private final ConcurrentHashMap<Long, CompletableFuture<MWZooKeeperResponse>> pendingWritesByZxid = new ConcurrentHashMap<>();
	// Follower uses correlationId to track requests it forwarded to leader
	private final ConcurrentHashMap<String, CompletableFuture<MWZooKeeperResponse>> pendingWritesByCorrelationId = new ConcurrentHashMap<>();

	// Correlation ID generation for follower requests
	private final AtomicLong correlationCounter = new AtomicLong(1);

	public MWZooKeeperServer(MWZooKeeperImpl impl, Properties zabProperties) throws IOException {
		this.impl = impl;
		this.zab = new MultiZab(zabProperties, this);
		this.clientIdGenerator = new MWZooKeeperIdGenerator(zabProperties.getProperty("myid"));
	}

	@Override
	public void deliverRequest(Serializable request) {
		if (currentStatus == ZabStatus.LEADING) {  // Leader processes the request
			logger.debug("Leader processing write request: {}", request);
			processWriteRequestAsLeader(request);
		} else if (currentStatus == ZabStatus.FOLLOWING) {  // Follower forwards to leader via Zab
			logger.debug("Follower forwarding request to leader: {}", request);
			zab.forwardRequest(request);
		}
		// If LOOKING drop request
	}

	@Override
	public void deliverTxn(Serializable txn, long zxid) {
		logger.debug("Delivering transaction with zxid: {}", zxid);

		if (!(txn instanceof MWZooKeeperTxn)) {
			logger.error("Received non-MWZooKeeperTxn object: {}", txn);
			return;
		}

		MWZooKeeperTxn zkTxn = (MWZooKeeperTxn) txn;

		// Apply transaction to state machine
		MWZooKeeperResponse response = impl.applyTxn(zkTxn, zxid);
		logger.debug("Transaction {} applied successfully", zxid);

		// Complete pending future if this was a synchronous write
		// Leader checks by zxid
		CompletableFuture<MWZooKeeperResponse> futureByZxid = pendingWritesByZxid.remove(zxid);
		if (futureByZxid != null) {
			logger.debug("Completing leader write future for zxid: {}", zxid);
			futureByZxid.complete(response);
		}

		// Follower checks by correlationId
		String correlationId = zkTxn.getCorrelationId();
		if (correlationId != null) {
			CompletableFuture<MWZooKeeperResponse> futureByCorrelation = pendingWritesByCorrelationId.remove(correlationId);
			if (futureByCorrelation != null) {
				logger.debug("Completing follower write future for correlationId: {}", correlationId);
				futureByCorrelation.complete(response);
			}
		}
	}

	@Override
	public void status(ZabStatus status, String leader) {
		logger.info("Zab status changed: {}, leader: {}", status, leader);
		ZabStatus previousStatus = this.currentStatus;
		this.currentStatus = status;

		if (status == ZabStatus.LEADING) {
			logger.info("I am now the LEADER");
		} else if (status == ZabStatus.FOLLOWING) {
			logger.info("I am now a FOLLOWER of {}", leader);
		} else if (status == ZabStatus.LOOKING) {
			logger.info("I am now LOOKING (election in progress)");
		}

		// Cancel all pending writes if we lose leadership or enter election
		if (previousStatus == ZabStatus.LEADING && status != ZabStatus.LEADING) {
			logger.warn("Lost leadership - canceling {} pending writes", pendingWritesByZxid.size());
			MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
			errorResp.setException(new MWZooKeeperException("Server lost leadership during write"));
			pendingWritesByZxid.values().forEach(future -> future.complete(errorResp));
			pendingWritesByZxid.clear();
		}

		// Cancel follower pending writes during election
		if (status == ZabStatus.LOOKING && !pendingWritesByCorrelationId.isEmpty()) {
			logger.warn("Entering election - canceling {} pending follower writes", pendingWritesByCorrelationId.size());
			MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
			errorResp.setException(new MWZooKeeperException("Server entering election during write"));
			pendingWritesByCorrelationId.values().forEach(future -> future.complete(errorResp));
			pendingWritesByCorrelationId.clear();
		}
	}

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

		// Copy correlationId from request to transaction (if present, from follower)
		if (zkRequest.getCorrelationId() != null) {
			txn.setCorrelationId(zkRequest.getCorrelationId());
			logger.debug("Transaction {} has correlationId: {}", zxid, zkRequest.getCorrelationId());
		}

		zab.proposeTxn(txn, zxid);
		logger.debug("Transaction with zxid {} proposed to Zab", zxid);
	}

	private MWZooKeeperResponse handleWriteRequestWithZab(MWZooKeeperRequest request) {
		if (currentStatus == ZabStatus.LEADING) {
			// Leader: process directly and WAIT for commit
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

			// Create future to wait for commit
			CompletableFuture<MWZooKeeperResponse> future = new CompletableFuture<>();
			pendingWritesByZxid.put(zxid, future);

			zab.proposeTxn(txn, zxid);
			logger.debug("Transaction with zxid {} proposed, waiting for commit", zxid);

			// SYNCHRONOUS: Wait for deliverTxn to complete the future
			try {
				MWZooKeeperResponse response = future.get(10, TimeUnit.SECONDS);
				logger.debug("Transaction {} committed successfully", zxid);
				return response;
			} catch (TimeoutException e) {
				logger.error("Timeout waiting for transaction {} to commit", zxid);
				pendingWritesByZxid.remove(zxid);
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Write operation timed out"));
				return errorResp;
			} catch (InterruptedException e) {
				logger.error("Interrupted while waiting for transaction {}", zxid);
				pendingWritesByZxid.remove(zxid);
				Thread.currentThread().interrupt();
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Write operation interrupted"));
				return errorResp;
			} catch (Exception e) {
				logger.error("Error waiting for transaction {}", zxid, e);
				pendingWritesByZxid.remove(zxid);
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Write operation failed: " + e.getMessage()));
				return errorResp;
			}

		} else if (currentStatus == ZabStatus.FOLLOWING) {
			// Follower: forward to leader via Zab and WAIT for commit
			logger.debug("Forwarding write request to leader as follower");

			// Generate unique correlationId to track this request
			String myId = clientIdGenerator.nextUniqueId().split("-")[0]; // Extract server ID part
			String correlationId = myId + "-" + System.nanoTime() + "-" + correlationCounter.getAndIncrement();
			request.setCorrelationId(correlationId);
			logger.debug("Generated correlationId: {} for follower write", correlationId);

			// Create future to wait for commit
			CompletableFuture<MWZooKeeperResponse> future = new CompletableFuture<>();
			pendingWritesByCorrelationId.put(correlationId, future);

			zab.forwardRequest(request);
			logger.debug("Request forwarded to leader with correlationId: {}, waiting for commit", correlationId);

			// SYNCHRONOUS: Wait for deliverTxn to complete the future
			try {
				MWZooKeeperResponse response = future.get(10, TimeUnit.SECONDS);
				logger.debug("Follower write with correlationId {} committed successfully", correlationId);
				return response;
			} catch (TimeoutException e) {
				logger.error("Timeout waiting for follower write {} to commit", correlationId);
				pendingWritesByCorrelationId.remove(correlationId);
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Write operation timed out"));
				return errorResp;
			} catch (InterruptedException e) {
				logger.error("Interrupted while waiting for follower write {}", correlationId);
				pendingWritesByCorrelationId.remove(correlationId);
				Thread.currentThread().interrupt();
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Write operation interrupted"));
				return errorResp;
			} catch (Exception e) {
				logger.error("Error waiting for follower write {}", correlationId, e);
				pendingWritesByCorrelationId.remove(correlationId);
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Write operation failed: " + e.getMessage()));
				return errorResp;
			}

		} else {
			// LOOKING state - cannot process writes during election
			logger.warn("Received write request while in LOOKING state");
			MWZooKeeperResponse resp = new MWZooKeeperResponse();
			resp.setException(new MWZooKeeperException("Cluster is in election - try again later"));
			return resp;
		}
	}

	public void start(int port) throws IOException {
		if (running) {
			logger.warn("Server already running on port {}", serverSocket.getLocalPort());
			return;
		}

		logger.info("Starting Zab protocol...");
		zab.startup();
		logger.info("Zab protocol started, leader election in progress");

		// Start the client-facing server
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
