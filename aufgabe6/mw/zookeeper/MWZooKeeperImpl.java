package mw.zookeeper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class MWZooKeeperImpl {

	private final Map<String, Node> zb = new HashMap<>();
	private final Map<String, Node> za = new HashMap<>();

	public MWZooKeeperImpl() {
		// Initialize root node so nodes can be created at the root level
		Node root = new Node(null, 0, System.currentTimeMillis(), 0, false, false, null);
		root.zxid = 0;
		zb.put("/", root);
	}

	private static class Node {
		byte[] data;
		int version;
		long time;
		long zxid;
		boolean ephemeral;
		boolean deleted;
		String clientId;

		Node() {}

		Node(byte[] data, int version, long time, long zxid, boolean ephemeral, boolean deleted, String clientId) {
			this.data = data;
			this.version = version;
			this.time = time;
			this.zxid = zxid;
			this.ephemeral = ephemeral;
			this.deleted = deleted;
			this.clientId = clientId;
		}
	}


	public synchronized MWZooKeeperResponse processReadRequest(MWZooKeeperRequest request) {
		String path = request.getPath();
		MWZooKeeperResponse resp = new MWZooKeeperResponse();
		Node node = zb.get(path);
		if (node == null || node.deleted) {
			resp.setException(new MWZooKeeperException("Node does not exist"));
			return resp;
		}
		// return a copy of data and a stat
		resp.setData(node.data == null ? null : Arrays.copyOf(node.data, node.data.length));
		MWZooKeeperStat stat = new MWZooKeeperStat(node.version, node.time, node.zxid);
		resp.setStat(stat);
		return resp;
	}


	public synchronized MWZooKeeperTxn processWriteRequest(MWZooKeeperRequest request, long zxid) {
		String path = request.getPath();
		MWZooKeeperTxn txn = new MWZooKeeperTxn();
		txn.setOperation(request.getOperation());
		txn.setPath(path);
        // this assumes that the request data is not mutable by the client after this point
		byte[] reqData = request.getData();
		txn.setData(reqData);
		txn.setVersion(request.getVersion());
		txn.setEphemeral(request.getEphemeral());
		txn.setClientId(request.getClientId());

		Node eff = getEffectiveNode(path);
		long now = System.currentTimeMillis();

		switch (request.getOperation()) {
		case CREATE: {
			// extract the parent path
			int lastSlashIndex = path.lastIndexOf("/");
			String parentPath = lastSlashIndex == -1 ? null : path.substring(0, lastSlashIndex);

			// If parentPath is empty string, it means this node is at root level (e.g., "/test")
			// In this case, set parentPath to "/" to represent the root node
			if (parentPath != null && parentPath.isEmpty()) {
				parentPath = "/";
			}

			if (parentPath != null && !parentPath.equals("/")) {
				// this node to be created is not on the root level and
				// therefore should have some parents
				// (we assume that the "root" always exist so you can create "/test" without creating "/" first)
				Node parentNode = getEffectiveNode(parentPath);
				if (parentNode == null) {
					// Abort case 1: Parent does not exist
					txn.setException(new MWZooKeeperException("Parent node does not exist!"));
					return txn;
				} else if (parentNode.ephemeral) {
					// Abort case 2: Parent exists, but is a ephemeral node
					// (ephemeral nodes have to be leave nodes)
					txn.setException(new MWZooKeeperException("Cannot create subnode as the parent node (" +
						parentPath +  ") is an ephemeral node!"));
					return txn;
				}
			}

			if (eff != null && !eff.deleted) {
				// cannot create an already existing node
				txn.setException(new MWZooKeeperException("Node already exists"));
				return txn;
			}
			// create node in ZA
			Node created = new Node(txn.getData(),
				0, now, zxid, request.getEphemeral(), false, request.getClientId());
			created.zxid = zxid;
			za.put(path, created);
			return txn;
		}
		case DELETE: {
			if (eff == null || eff.deleted) {
				txn.setException(new MWZooKeeperException("Node does not exist"));
				return txn;
			}
			if (request.getVersion() != -1 && request.getVersion() != eff.version) {
				txn.setException(new MWZooKeeperException("Version mismatch"));
				return txn;
			}
			Node del = new Node(null, eff.version, now, zxid, eff.ephemeral, true, request.getClientId());
			del.zxid = zxid;
			za.put(path, del);
			txn.setDelete(true);
			return txn;
		}
		case CLEANUP: {
			// create del nodes for all nodes that were created by that client
			List<Entry<String, Node>> targetNodes = zb.entrySet()
				.stream()
				.filter(entry -> entry.getValue().ephemeral && entry.getValue().clientId.equals(request.getClientId()))
				.collect(Collectors.toList()); // collect as modifying the map while iterating might be undefined behavior

			targetNodes.forEach(entry -> {
				Node del = new Node(null, eff.version, now, zxid, eff.ephemeral, true, request.getClientId());
				del.zxid = zxid;
				za.put(entry.getKey(), del);
			});

			txn.setDelete(true);
			return txn;
		}
		case SET_DATA: {
			if (eff == null || eff.deleted) {
				txn.setException(new MWZooKeeperException("Node does not exist"));
				return txn;
			}
			if (request.getVersion() != -1 && request.getVersion() != eff.version) {
				txn.setException(new MWZooKeeperException("Version mismatch"));
				return txn;
			}
			int newVersion = eff.version + 1;
			Node updated = new Node(txn.getData(),
				newVersion, now, zxid, eff.ephemeral, false, request.getClientId());
			updated.zxid = zxid;
			za.put(path, updated);
			txn.setVersion(newVersion);
			return txn;
		}
		default: {
			txn.setException(new MWZooKeeperException("Unknown operation"));
			return txn;			
		}
		}
	}


	public synchronized MWZooKeeperResponse applyTxn(MWZooKeeperTxn txn, long zxid) {
		MWZooKeeperResponse resp = new MWZooKeeperResponse();
		if (txn == null) {
			resp.setException(new MWZooKeeperException("Null transaction"));
			return resp;
		}
		if (txn.isError()) {
			resp.setException(txn.getException());
			// No state change; cleanup ZA entries that belong exactly to this zxid
			cleanupZAForZxid(txn.getPath(), zxid);
			return resp;
		}

		String path = txn.getPath();
		switch (txn.getOperation()) {
		case CREATE: {
			Node n = new Node(txn.getData(),
				0, System.currentTimeMillis(), zxid, txn.isEphemeral(), false, txn.getClientId());
			n.zxid = zxid;
			zb.put(path, n);
			resp.setPath(path);
			resp.setStat(new MWZooKeeperStat(n.version, n.time, n.zxid));
			// remove matching ZA entry if it corresponds to this zxid
			cleanupZAForZxid(path, zxid);
			return resp;
		}
		case DELETE: {
			// Remove from confirmed state if exists
			Node existing = zb.get(path);
			if (existing != null) zb.remove(path);
			resp.setPath(path);
			// cleanup ZA
			cleanupZAForZxid(path, zxid);
			return resp;
		}
		case CLEANUP: {
			// Remove from confirmed state if exists
			List<String> pathsToRemove = zb.entrySet()
				.stream()
				.filter(entry -> entry.getValue().ephemeral && entry.getValue().clientId.equals(txn.getClientId()))
				.map(entry -> entry.getKey())
				.collect(Collectors.toList()); // collect as removing while iterating might be undefined behavior

			for (String pathToRemove : pathsToRemove) {
				zb.remove(pathToRemove);
			}

			// cleanup ZA
			cleanupZAForZxid(path, zxid);
			return resp;
		}
		case SET_DATA: {
			Node existing = zb.get(path);
			if (existing == null) {
				// follower may not have the node yet; create it to mirror leader
				existing = new Node();
				existing.data = txn.getData();
				existing.version = txn.getVersion();
				existing.time = System.currentTimeMillis();
				existing.zxid = zxid;
				existing.deleted = false;
				zb.put(path, existing);
				resp.setStat(new MWZooKeeperStat(existing.version, existing.time, existing.zxid));
				cleanupZAForZxid(path, zxid);
				return resp;
			} else {
				existing.data = txn.getData();
				existing.version = txn.getVersion();
				existing.time = System.currentTimeMillis();
				existing.zxid = zxid;
				resp.setStat(new MWZooKeeperStat(existing.version, existing.time, existing.zxid));
				cleanupZAForZxid(path, zxid);
				return resp;
			}
		}
		default:
			resp.setException(new MWZooKeeperException("Unknown operation"));
			return resp;
		}
	}

	private Node getEffectiveNode(String path) {
		// Check ZA first for leader current state
		Node n = za.get(path);
		if (n != null) return n;
		Node confirmed = zb.get(path);
		return confirmed;
	}

	private void cleanupZAForZxid(String path, long zxid) {
		Node n = za.get(path);
		if (n != null && n.zxid == zxid) {
			za.remove(path);
		}
	}

}
