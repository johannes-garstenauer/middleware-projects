package mw.zookeeper;


/**
 * Class for signaling ZooKeeper-specific exceptions.
 */
public class MWZooKeeperException extends Exception {

	public MWZooKeeperException(String message) {
		super(message);
	}

}
