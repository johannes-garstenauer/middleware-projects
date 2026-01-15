package mw.zookeeper;

import java.io.Serializable;


/**
 * Class representing the meta data of a ZooKeeper node. 
 */
public class MWZooKeeperStat implements Serializable {

	private int version;
	private long time;
	private long zxid;

	
	public MWZooKeeperStat() {
		this(0, -1L, -1L);
	}

	public MWZooKeeperStat(int version, long time, long zxid) {
		this.version = version;
		this.time = time;
		this.zxid = zxid;
	}
	
	
	public void copyTo(MWZooKeeperStat destinationStat) {
		if(destinationStat == null) return;
		destinationStat.version = version;
		destinationStat.time = time;
		destinationStat.zxid = zxid;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public int getVersion() {
		return version;
	}

	public void setLastModifiedTime(long time) {
		this.time = time;
	}
	
	public long getLastModifiedTime() {
		return time;
	}
	
	public void setLastModifiedZXID(long zxid) {
		this.zxid = zxid;
	}

	public long getLastModifiedZXID() {
		return zxid;
	}

}
