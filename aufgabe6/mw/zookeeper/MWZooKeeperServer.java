package mw.zookeeper;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.zab.SingleZab;
import org.apache.zookeeper.zab.Txn;
import org.apache.zookeeper.zab.ZabCallback;
import org.apache.zookeeper.zab.ZabStatus;

// Determine requirements (interaction Client <-> IMPL <-> ZAB
// Walk through code
// Test Interaction on SingleZab
// Change SingleZab to MultiZab and test on MultiZab
// Pass entire exercise sheet+handout and see if all specifications (regarding zab and how it interfaces with IMPl are fulfilled)

// TODO: refactor into separate Zab classes
// TODO: remove the whole delay test nonsense
public class MWZooKeeperServer implements ZabCallback {

	private static final Logger logger = LogManager.getLogger(MWZooKeeperServer.class);

	private final MWZooKeeperImpl impl;
    // consider hashing lol
	private final AtomicLong nextZXID = new AtomicLong(1);
	private ServerSocket serverSocket;
	private volatile boolean running = false;
	private Thread acceptThread;

	private SingleZab zab;
	private volatile ZabStatus currentStatus = ZabStatus.LOOKING; // On init leader still undetermined


	// Helper methods to serialize/deserialize objects for Zab Txn
	private static byte[] serializeToBytes(Serializable obj) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(obj);
		oos.flush();
		return baos.toByteArray();
	}

	private static Serializable deserializeFromBytes(byte[] data) throws IOException, ClassNotFoundException {
		ByteArrayInputStream bais = new ByteArrayInputStream(data);
		ObjectInputStream ois = new ObjectInputStream(bais);
		return (Serializable) ois.readObject();
	}


	public MWZooKeeperServer(MWZooKeeperImpl impl, Properties zabProperties) throws IOException {
		this.impl = impl;
		this.zab = new SingleZab(zabProperties, this);
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
			try {
				// Serialize request to byte array for Txn
				byte[] requestData = serializeToBytes(request);
				Txn zabTxn = new Txn(0, 0, 0, System.currentTimeMillis(), requestData, false);
				zab.deliver(zabTxn);
			} catch (IOException e) {
				logger.error("Error forwarding request to leader", e);
			}
		}
		// If LOOKING, drop request or queue it
	}

	@Override
	public void deliverTxn(Serializable txn, long zxid) {
		// Called when a transaction has been committed by majority
		// Apply to local state machine
		logger.debug("Delivering transaction with zxid: {}", zxid);

		// Deserialize the transaction from Txn's byte[] data field
		try {
			if (!(txn instanceof Txn)) {
				logger.error("Received non-Txn object: {}", txn);
				return;
			}

			Txn zabTxn = (Txn) txn;
			byte[] data = zabTxn.getData();
			Serializable payload = deserializeFromBytes(data);

			if (!(payload instanceof MWZooKeeperTxn)) {
				logger.error("Received non-MWZooKeeperTxn payload: {}", payload);
				return;
			}

			MWZooKeeperTxn zkTxn = (MWZooKeeperTxn) payload;
			impl.applyTxn(zkTxn, zxid);
			logger.debug("Transaction {} applied successfully", zxid);
		} catch (IOException | ClassNotFoundException e) {
			logger.error("Error deserializing transaction", e);
		}
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

		// Create transaction
		MWZooKeeperTxn txn = impl.processWriteRequest(zkRequest, zxid);

		// Serialize to byte array and wrap in Zab's Txn, then propose via Zab
		try {
			byte[] txnData = serializeToBytes(txn);
			Txn zabTxn = new Txn(zxid, 0, 0, System.currentTimeMillis(), txnData, false);
			zab.deliver(zabTxn);
			logger.debug("Transaction with zxid {} proposed to Zab", zxid);
		} catch (IOException e) {
			logger.error("Error proposing transaction with zxid {}", zxid, e);
		}
	}

	public void start(int port) throws IOException {
		if (running) {
			logger.warn("Server already running on port {}", serverSocket.getLocalPort());
			return;
		}
		serverSocket = new ServerSocket(port);
		running = true;
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
		logger.info("Server stopped");
	}

	private void acceptLoop() {
		logger.debug("Accept loop started");
		while (running) {
			try {
				Socket client = serverSocket.accept();
				logger.debug("Accepted new client connection from {}", client.getRemoteSocketAddress());
				Thread worker = new Thread(() -> handleClient(client), "MWZooKeeper-Worker");
				worker.setDaemon(true);
				worker.start();
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
		try {
			socket.setTcpNoDelay(true);
			ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
			out.flush();
			ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

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
					case DELETE:
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
		} catch (IOException | ClassNotFoundException e) {
			logger.error("Client handler error", e);
			try { socket.close(); } catch (IOException ignored) {}
		}
	}

	private MWZooKeeperResponse handleWriteRequestWithZab(MWZooKeeperRequest request) {
		if (currentStatus == ZabStatus.LEADING) {
			// Leader: process directly and return immediately
			logger.debug("Handling write request as leader");
			long zxid = nextZXID.getAndIncrement();

			// Create transaction and propose via Zab
			MWZooKeeperTxn txn = impl.processWriteRequest(request, zxid);

			try {
				// Serialize to byte array and wrap in Zab's Txn
				byte[] txnData = serializeToBytes(txn);
				Txn zabTxn = new Txn(zxid, 0, 0, System.currentTimeMillis(), txnData, false);
				zab.deliver(zabTxn);
				logger.debug("Transaction with zxid {} proposed", zxid);

				// Return success immediately (fire-and-forget)
				MWZooKeeperResponse resp = new MWZooKeeperResponse();
				return resp;
			} catch (IOException e) {
				logger.error("Failed to propose transaction with zxid {}", zxid, e);
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Failed to propose: " + e));
				return errorResp;
			}

		} else if (currentStatus == ZabStatus.FOLLOWING) {
			// Follower: forward to leader via Zab and return immediately
			logger.debug("Forwarding write request to leader as follower");
			try {
				// Serialize request to byte array and wrap in Zab's Txn
				byte[] requestData = serializeToBytes(request);
				Txn zabTxn = new Txn(0, 0, 0, System.currentTimeMillis(), requestData, false);
				zab.deliver(zabTxn);
				logger.debug("Request forwarded to leader");

				// Return success immediately (fire-and-forget)
				MWZooKeeperResponse resp = new MWZooKeeperResponse();
				return resp;
			} catch (IOException e) {
				logger.error("Failed to forward request to leader", e);
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Failed to forward to leader: " + e));
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
