package mw.path;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;


public class MWDijkstra {

	/*
	 * Calculates the shortest path between startID and endID.
	 */
	public static String[] getShortestPath(String startID, String endID, Map<String, Collection<String>> friendships) {
		// Check arguments
		if(startID == null) throw new NullPointerException("[Dijkstra] The parameter 'startID' must not be null");
		if(endID == null) throw new NullPointerException("[Dijkstra] The parameter 'endID' must not be null");
		if(friendships == null) throw new NullPointerException("[Dijkstra] The parameter 'friendships' must not be null");
		if(friendships.isEmpty()) throw new IllegalArgumentException("[Dijkstra] The map 'friendships' contains no elements");
		
		// Dijkstra's algorithm
		System.out.println("[Dijkstra] startID = " + startID + ", endID = " + endID);
		long startTime = System.currentTimeMillis();
	
		// Initialization
		Map<String, MWDijkstraNode> nodes = new HashMap<String, MWDijkstraNode>();
		Queue<MWDijkstraNode> distances = new PriorityQueue<MWDijkstraNode>();
		for(Entry<String, Collection<String>> entry: friendships.entrySet()) {
			// Check entry
			String id = entry.getKey();
			Collection<String> friendIDs = entry.getValue();
			if(id == null) continue;
			if((friendIDs == null) || (friendIDs.isEmpty())) continue;

			// Create node if necessary
			MWDijkstraNode node = nodes.get(id);
			if(node == null) {
				node = new MWDijkstraNode(id, id.equals(startID));
				nodes.put(id, node);
				distances.add(node);
			}
			
			// Process friends
			for(String friendID: friendIDs) {
				// Create friend node if necessary
				MWDijkstraNode friend = nodes.get(friendID);
				if(friend == null) {
					friend = new MWDijkstraNode(friendID, friendID.equals(startID));
					nodes.put(friendID, friend);
					distances.add(friend);
				}
				
				// Store friendship
				node.addFriend(friendID);
				friend.addFriend(id);
			}
		}
		if(!nodes.containsKey(startID)) throw new IllegalArgumentException("[Dijkstra] The map 'friendships' must contain the start ID");
		if(!nodes.containsKey(endID)) throw new IllegalArgumentException("[Dijkstra] The map 'friendships' must contain the end ID");
		
		// Create predecessor information
		for(MWDijkstraNode node = distances.poll(); node != null; node = distances.poll()) {
			if((distances.size() % 500) == 0) System.out.println(String.format("[Dijkstra] %4d nodes remaining to be processed", distances.size()));
			for(String friendID: node.getFriends()) {
				// Get friend
				MWDijkstraNode friend = nodes.get(friendID);
				if(friend == null) continue;
				
				// Try to minimize the friend's distance
				int alternative = node.getDistance() + 1;
				if(friend.getDistance() <= alternative) continue;
				
				// Update the friend's distance and predecessor information
				friend.setDistance(alternative);
				friend.setPredecessor(node);
				
				// Update the friend's position in the priority queue
				distances.remove(friend);
				distances.add(friend);
			}
		}
		
		// Extract and return shortest path
		LinkedList<String> path = new LinkedList<String>();
		for(MWDijkstraNode node = nodes.get(endID); node != null; node = node.getPredecessor()) path.addFirst(node.getID());
		System.out.println("[Dijkstra] Completed in " + (System.currentTimeMillis() - startTime) + "ms (Path size: " + path.size() + ")");
		return path.toArray(new String[path.size()]);
	}

	
	/*
	 * Helper class for calculating the shortest path between two nodes.
	 */
	public static class MWDijkstraNode implements Comparable<MWDijkstraNode> {

		private final String id;
		private final Collection<String> friends;
		private int distance;
		private MWDijkstraNode predecessor;

		
		public MWDijkstraNode(String id, boolean isStart) {
			this.id = id;
			this.friends = new LinkedHashSet<String>();
			this.distance = isStart ? 0 : Integer.MAX_VALUE;
			this.predecessor = null;
		}


		public String getID() {
			return id;
		}

		public int getDistance() {
			return distance;
		}

		public void setDistance(int distance) {
			this.distance = distance;
		}

		public Collection<String> getFriends() {
			return friends;
		}
		
		public void addFriend(String friend) {
			friends.add(friend);
		}

		public MWDijkstraNode getPredecessor() {
			return predecessor;
		}

		public void setPredecessor(MWDijkstraNode predecessor) {
			this.predecessor = predecessor;
		}

		public int compareTo(MWDijkstraNode o) {
			return (distance - o.distance);
		}
		
		@Override
		public boolean equals(Object object) {
			return (object instanceof MWDijkstraNode) ? id.equals(((MWDijkstraNode) object).id) : false;
		}
		
		@Override
		public String toString() {
			return "{" + id + "#" + distance + "}";
		}

	}

}
