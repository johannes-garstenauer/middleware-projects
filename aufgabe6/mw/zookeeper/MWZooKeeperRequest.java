package mw.zookeeper;

import java.io.Serializable;


/**
 * Class representing the information necessary to invoke a method at the ZooKeeper service. 
 */
public class MWZooKeeperRequest implements Serializable {

	private MWZooKeeperOperation operation;
	private String path;
	private int version;
	private byte[] data;
	private boolean ephemeral;
	private String clientId;
	
	
	public MWZooKeeperRequest(MWZooKeeperOperation operation, String path) {
		this.operation = operation;
		this.path = path;
		this.version = -1;
		this.data = null;
		this.clientId = null;
		this.ephemeral = false;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getClientId() {
		return clientId;
	}

	public MWZooKeeperOperation getOperation() {
		return operation;
	}

	public String getPath() {
		return path;
	}

	public void setVersion(int version) {
		this.version = version;
	}
	
	public int getVersion() {
		return version;
	}
	
	public void setData(byte[] data) {
		this.data = data;
	}
	
	public byte[] getData() {
		return data;
	}
	
	public void setEphemeral(boolean ephemeral) {
		this.ephemeral = ephemeral;
	}
	
	public boolean getEphemeral() {
		return ephemeral;
	}

}
