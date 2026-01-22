package mw.zookeeper;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicLong;

public class MWZooKeeperServer {

	private final MWZooKeeperImpl impl;
    // consider hashing lol
	private final AtomicLong nextZXID = new AtomicLong(1);
	private ServerSocket serverSocket;
	private volatile boolean running = false;
	private Thread acceptThread;

	public MWZooKeeperServer(MWZooKeeperImpl impl) {
		this.impl = impl;
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
						response = impl.processReadRequest(request);
						break;
					case CREATE:
					case DELETE:
					case SET_DATA:
						long zxid = nextZXID.getAndIncrement();
						MWZooKeeperTxn txn = impl.processWriteRequest(request, zxid);
						response = impl.applyTxn(txn, zxid);
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


	public static void main(String[] args) throws Exception {
		int port = 2181;
		if (args.length > 0) port = Integer.parseInt(args[0]);
		MWZooKeeperImpl impl = new MWZooKeeperImpl();
		MWZooKeeperServer server = new MWZooKeeperServer(impl);
		System.out.println("Starting MWZooKeeperServer on port " + port);
		server.start(port);
		Thread.currentThread().join();
	}

}
