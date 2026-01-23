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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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

	private final MWZooKeeperImpl impl;
    // consider hashing lol
	private final AtomicLong nextZXID = new AtomicLong(1);
	private ServerSocket serverSocket;
	private volatile boolean running = false;
	private Thread acceptThread;

	private SingleZab zab;
	private volatile ZabStatus currentStatus = ZabStatus.LOOKING; // On init leader still undetermined
	private volatile String leaderAddress = null; // TODO: unused

	// For handling responses to clients after commit
	private final ConcurrentHashMap<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

	// Helper class to track pending write requests awaiting commit
	private static class PendingRequest {
		final Lock lock = new ReentrantLock();
		final Condition commitReceived = lock.newCondition();
		volatile MWZooKeeperResponse response = null;
		volatile boolean committed = false;
	}

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

	public MWZooKeeperServer(MWZooKeeperImpl impl) {
		this.impl = impl;
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
			processWriteRequestAsLeader(request);
		} else if (currentStatus == ZabStatus.FOLLOWING) {
			// Follower forwards to leader via Zab
			try {
				// Serialize request to byte array for Txn
				byte[] requestData = serializeToBytes(request);
				Txn zabTxn = new Txn(0, 0, 0, System.currentTimeMillis(), requestData, false);
				zab.deliver(zabTxn);
			} catch (IOException e) {
				System.err.println("Error forwarding request to leader: " + e);
			}
		}
		// If LOOKING, drop request or queue it
	}

	@Override
	public void deliverTxn(Serializable txn, long zxid) {
		// Called when a transaction has been committed by majority
		// Apply to local state machine

		// Deserialize the transaction from Txn's byte[] data field
		try {
			if (!(txn instanceof Txn)) {
				System.err.println("Received non-Txn object: " + txn);
				return;
			}

			Txn zabTxn = (Txn) txn;
			byte[] data = zabTxn.getData();
			Serializable payload = deserializeFromBytes(data);

			if (!(payload instanceof MWZooKeeperTxn)) {
				System.err.println("Received non-MWZooKeeperTxn payload: " + payload);
				return;
			}

			MWZooKeeperTxn zkTxn = (MWZooKeeperTxn) payload;
			MWZooKeeperResponse response = impl.applyTxn(zkTxn, zxid);

			// If this server is leader and originated this request, notify waiting client
			PendingRequest pending = pendingRequests.remove(zxid);
			if (pending != null) {
				pending.lock.lock();
				try {
					pending.response = response;
					pending.committed = true;
					pending.commitReceived.signalAll();
				} finally {
					pending.lock.unlock();
				}
			}
		} catch (IOException | ClassNotFoundException e) {
			System.err.println("Error deserializing transaction: " + e);
		}
	}

	@Override
	public void status(ZabStatus status, String leader) {
		System.out.println("Zab status changed: " + status + ", leader: " + leader);
		this.currentStatus = status;
		this.leaderAddress = leader;

		// On becoming leader, process any queued requests
		if (status == ZabStatus.LEADING) {
			System.out.println("I am now the LEADER");
		} else if (status == ZabStatus.FOLLOWING) {
			System.out.println("I am now a FOLLOWER of " + leader);
		}
	}

	// Process write request as leader
	private void processWriteRequestAsLeader(Serializable request) {
		if (!(request instanceof MWZooKeeperRequest)) {
			System.err.println("Received non-MWZooKeeperRequest: " + request);
			return;
		}

		MWZooKeeperRequest zkRequest = (MWZooKeeperRequest) request;
		long zxid = nextZXID.getAndIncrement();

		// Create transaction
		MWZooKeeperTxn txn = impl.processWriteRequest(zkRequest, zxid);

		// Serialize to byte array and wrap in Zab's Txn, then propose via Zab
		try {
			byte[] txnData = serializeToBytes(txn);
			Txn zabTxn = new Txn(zxid, 0, 0, System.currentTimeMillis(), txnData, false);
			zab.deliver(zabTxn);
		} catch (IOException e) {
			System.err.println("Error proposing transaction: " + e);
		}
	}

	public void start(int port) throws IOException {
		if (running) return;
		serverSocket = new ServerSocket(port);
		running = true;
		acceptThread = new Thread(this::acceptLoop, "MWZooKeeper-AcceptThread");
		acceptThread.setDaemon(true);
		acceptThread.start();
	}

	public void stop() throws IOException {
		running = false;
		if (serverSocket != null) serverSocket.close();
		if (acceptThread != null) {
			try { acceptThread.join(1000); } catch (InterruptedException ignored) {}
		}
	}

	private void acceptLoop() {
		while (running) {
			try {
				Socket client = serverSocket.accept();
				Thread worker = new Thread(() -> handleClient(client), "MWZooKeeper-Worker");
				worker.setDaemon(true);
				worker.start();
			} catch (SocketException se) {
				// Socket closed during shutdown
				break;
			} catch (IOException e) {
				System.err.println("Error accepting connection: " + e);
			}
		}
	}

	private void handleClient(Socket socket) {
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
					break;
				}
				if (!(obj instanceof MWZooKeeperRequest)) {
					// Unknown object; ignore and continue
					continue;
				}
				MWZooKeeperRequest request = (MWZooKeeperRequest) obj;
				MWZooKeeperResponse response;
				try {
					switch (request.getOperation()) {
					case GET_DATA:
						// Reads are served locally (may return stale data)
						response = impl.processReadRequest(request);
						break;
					case CREATE:
					case DELETE:
					case SET_DATA:
						// Write operations must go through Zab
						if (zab == null) {
							// Single-node mode (no replication)
							long zxid = nextZXID.getAndIncrement();
							MWZooKeeperTxn txn = impl.processWriteRequest(request, zxid);
							response = impl.applyTxn(txn, zxid);
						} else {
							response = handleWriteRequestWithZab(request);
						}
						break;
					default:
						response = new MWZooKeeperResponse();
						response.setException(new MWZooKeeperException("Unknown operation"));
					}
				} catch (Exception e) {
					response = new MWZooKeeperResponse();
					response.setException(new MWZooKeeperException(e.toString()));
				}
				try {
					out.writeObject(response);
					out.flush();
				} catch (IOException ioe) {
					// Broken pipe / client disconnected
					break;
				}
			}

			try { in.close(); } catch (IOException ignored) {}
			try { out.close(); } catch (IOException ignored) {}
			socket.close();
		} catch (IOException | ClassNotFoundException e) {
			System.err.println("Client handler error: " + e);
			try { socket.close(); } catch (IOException ignored) {}
		}
	}

	private MWZooKeeperResponse handleWriteRequestWithZab(MWZooKeeperRequest request) {
		if (currentStatus == ZabStatus.LEADING) {
			// Leader: process directly
			long zxid = nextZXID.getAndIncrement();

			// Register pending request to get response after commit
			PendingRequest pending = new PendingRequest();
			pendingRequests.put(zxid, pending);

			// Create transaction and propose via Zab
			MWZooKeeperTxn txn = impl.processWriteRequest(request, zxid);

			try {
				// Serialize to byte array and wrap in Zab's Txn
				byte[] txnData = serializeToBytes(txn);
				Txn zabTxn = new Txn(zxid, 0, 0, System.currentTimeMillis(), txnData, false);
				zab.deliver(zabTxn);
			} catch (IOException e) {
				pendingRequests.remove(zxid);
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Failed to propose: " + e));
				return errorResp;
			}

			// Wait for commit
			pending.lock.lock();
			try {
				while (!pending.committed) {
					try {
						pending.commitReceived.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
						errorResp.setException(new MWZooKeeperException("Interrupted waiting for commit"));
						return errorResp;
					}
				}
				return pending.response;
			} finally {
				pending.lock.unlock();
			}

		} else if (currentStatus == ZabStatus.FOLLOWING) {
			// Follower: forward to leader via Zab
			try {
				// Serialize request to byte array and wrap in Zab's Txn
				byte[] requestData = serializeToBytes(request);
				Txn zabTxn = new Txn(0, 0, 0, System.currentTimeMillis(), requestData, false);
				zab.deliver(zabTxn);
			} catch (IOException e) {
				MWZooKeeperResponse errorResp = new MWZooKeeperResponse();
				errorResp.setException(new MWZooKeeperException("Failed to forward to leader: " + e));
				return errorResp;
			}

			// TODO: hate this
			// For simplicity in this implementation, return an acknowledgment
			// In a real system, we'd need a more sophisticated request tracking mechanism
			MWZooKeeperResponse resp = new MWZooKeeperResponse();
			resp.setException(new MWZooKeeperException("Request forwarded to leader - reconnect to get result"));
			return resp;

		} else {
			// LOOKING state
			MWZooKeeperResponse resp = new MWZooKeeperResponse();
			resp.setException(new MWZooKeeperException("Cluster is in election - try again later"));
			return resp;
		}
	}


	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.out.println("Usage: MWZooKeeperServer <port> [<myid> <peers> [--delay-apply-ms=<ms>]]");
			System.out.println("  Single-node mode: MWZooKeeperServer <port>");
			System.out.println("  Replicated mode: MWZooKeeperServer <port> <myid> <peer1,peer2,...> [--delay-apply-ms=<ms>]");
			System.out.println("  Example peers format: localhost:2181,localhost:2182,localhost:2183");
			System.exit(1);
		}

		int port = Integer.parseInt(args[0]);
		MWZooKeeperImpl impl = new MWZooKeeperImpl();
		MWZooKeeperServer server;

		if (args.length == 1) {
			// Single-node mode (no replication)
			server = new MWZooKeeperServer(impl);
			System.out.println("Starting MWZooKeeperServer (single-node mode) on port " + port);
		} else {
			// Replicated mode with Zab
			String myid = args[1];
			String peersStr = args[2];

			// Build Zab properties
			Properties zabProperties = new Properties();
			zabProperties.setProperty("myid", myid);

			String[] peers = peersStr.split(",");
			for (int i = 0; i < peers.length; i++) {
				zabProperties.setProperty("peer" + (i + 1), peers[i]);
			}

			server = new MWZooKeeperServer(impl, zabProperties);
			System.out.println("Starting MWZooKeeperServer (replicated mode) on port " + port + " with ID " + myid);
			System.out.println("Peers: " + peersStr);
		}

		server.start(port);
		System.out.println("Server started successfully");
		Thread.currentThread().join();
	}

}
