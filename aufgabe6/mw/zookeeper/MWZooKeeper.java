package mw.zookeeper;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;


/**
 * Proxy class allowing a client to access the ZooKeeper service.
 * 
 * Note: The implementation of this class is not thread-safe.
 */
public class MWZooKeeper {

	// #####################
	// # ZOOKEEPER METHODS #
	// #####################

	public String create(String path, byte[] data, boolean ephemeral) throws MWZooKeeperException {
		MWZooKeeperRequest request = new MWZooKeeperRequest(MWZooKeeperOperation.CREATE, path);
		request.setData(data);
		request.setEphemeral(ephemeral);
		MWZooKeeperResponse response = sendReceive(request);
		return response.getPath(); // Throws an exception in case of error
	}

	public void delete(String path, int version) throws MWZooKeeperException {
		MWZooKeeperRequest request = new MWZooKeeperRequest(MWZooKeeperOperation.DELETE, path);
		request.setVersion(version);
		MWZooKeeperResponse response = sendReceive(request);
		response.getPath(); // Throws an exception in case of error
	}

	public MWZooKeeperStat setData(String path, byte[] data, int version) throws MWZooKeeperException {
		MWZooKeeperRequest request = new MWZooKeeperRequest(MWZooKeeperOperation.SET_DATA, path);
		request.setData(data);
		request.setVersion(version);
		MWZooKeeperResponse response = sendReceive(request);
		return response.getStat(); // Throws an exception in case of error
	}
	
	public byte[] getData(String path, MWZooKeeperStat stat) throws MWZooKeeperException {
		MWZooKeeperRequest request = new MWZooKeeperRequest(MWZooKeeperOperation.GET_DATA, path);
		MWZooKeeperResponse response = sendReceive(request);
		MWZooKeeperStat responseStat = response.getStat(); // Throws an exception in case of error
		if(responseStat == null) throw new MWZooKeeperException("The response did not include a stat object");
		responseStat.copyTo(stat);
		return response.getData();
	}


	// ##########################
	// # COMMUNICATION HANDLING #
	// ##########################

	private Socket socket;
	private ObjectOutputStream socketOutput;
	private ObjectInputStream socketInput;


	public void openConnection(String host, int port) throws IOException {
		socket = new Socket(host, port);
		socket.setTcpNoDelay(true);
		socketOutput = new ObjectOutputStream(socket.getOutputStream());
		socketInput = new ObjectInputStream(socket.getInputStream());
	}
	
	public void closeConnection() throws IOException {
		socketOutput.close();
		socketInput.close();
		socket.close();
	}

	private MWZooKeeperResponse sendReceive(MWZooKeeperRequest request) throws MWZooKeeperException {
		// Check whether connection has been established
		if((socketOutput == null) || (socketInput == null)) throw new MWZooKeeperException("No connection to ZooKeeper. Please call openConnection() first.");
		
		// Exchange messages
		try {
			socketOutput.writeObject(request);
			socketOutput.flush();
			return (MWZooKeeperResponse) socketInput.readObject();
		} catch(Exception e) {
			throw new MWZooKeeperException(e.toString());
		}
	}

}
