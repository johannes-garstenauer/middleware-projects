package mw.zookeeper;


/**
 * Enumeration representing a ZooKeeper operation.
 */
public enum MWZooKeeperOperation {

	CREATE,
	DELETE,
	CLEANUP, // delete ephemeral nodes when client disconnects
	GET_DATA,
	SET_DATA

}
