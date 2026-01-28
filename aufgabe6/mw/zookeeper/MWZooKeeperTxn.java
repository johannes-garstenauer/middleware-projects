package mw.zookeeper;

import java.io.Serializable;


public class MWZooKeeperTxn implements Serializable {
	private static final long serialVersionUID = 1L;

	private MWZooKeeperOperation operation;
	private String path;
	private byte[] data;
	private int version; // version that will be set if txn is applied
	private boolean ephemeral;
	private boolean delete;
	private String clientId;
	private String correlationId; // For tracking follower requests through Zab
	private MWZooKeeperException exception; // non-null if this is an error transaction

	public MWZooKeeperTxn() {}

	public MWZooKeeperOperation getOperation() { return operation; }
	public void setOperation(MWZooKeeperOperation operation) { this.operation = operation; }

	public String getPath() { return path; }
	public void setPath(String path) { this.path = path; }

	public String getClientId() { return clientId; }
	public void setClientId(String clientId) { this.clientId = clientId; }

	public byte[] getData() { return data; }
	public void setData(byte[] data) { this.data = data; }

	public int getVersion() { return version; }
	public void setVersion(int version) { this.version = version; }

	public boolean isEphemeral() { return ephemeral; }
	public void setEphemeral(boolean ephemeral) { this.ephemeral = ephemeral; }

	public boolean isDelete() { return delete; }
	public void setDelete(boolean delete) { this.delete = delete; }

	public MWZooKeeperException getException() { return exception; }
	public void setException(MWZooKeeperException exception) { this.exception = exception; }

	public String getCorrelationId() { return correlationId; }
	public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

	public boolean isError() { return exception != null; }

}
