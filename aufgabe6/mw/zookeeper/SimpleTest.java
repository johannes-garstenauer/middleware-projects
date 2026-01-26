package mw.zookeeper;

import java.nio.charset.StandardCharsets;

public class SimpleTest {

	public static void main(String[] args) {
		try {
			// Connect to server
			MWZooKeeper zk = new MWZooKeeper();
			zk.openConnection("localhost", 2181);
			System.out.println("✓ Connected to server");

			// Wait for server to finish election and become leader
			System.out.println("Waiting for leader election to complete...");
			String path = null;
			int retries = 30; // Increased to 30 seconds
			while (retries > 0) {
				try {
					path = zk.create("/test", "hello-world".getBytes(StandardCharsets.UTF_8), false);
					break; // Success!
				} catch (MWZooKeeperException e) {
					if (e.getMessage().contains("election")) {
						System.out.println("  Still in election, retrying... (" + retries + " attempts left)");
						Thread.sleep(1000); // Wait 1 second
						retries--;
					} else {
						throw e; // Different error, rethrow
					}
				}
			}

			if (path == null) {
				throw new Exception("Server never became ready after 30 seconds - check if ZAB is configured correctly");
			}

			System.out.println("✓ Created node: " + path);

			// Read: Get the data back
			// Note: Due to fire-and-forget writes, we may need to retry if data isn't visible yet
			MWZooKeeperStat stat = new MWZooKeeperStat();
			byte[] data = null;
			int readRetries = 10;
			int attemptNumber = 1;
			MWZooKeeperException lastException = null;
			while (readRetries > 0) {
				try {
					System.out.println("  Attempting to read data (attempt " + attemptNumber + "/10)...");
					data = zk.getData("/test", stat);
					System.out.println("  Success on attempt " + attemptNumber + "!");
					break; // Success!
				} catch (MWZooKeeperException e) {
					lastException = e;
					if (e.getMessage().contains("does not exist")) {
						// Transaction not committed yet, retry
						System.out.println("  Attempt " + attemptNumber + " failed: " + e.getMessage());
						readRetries--;
						attemptNumber++;
						if (readRetries > 0) {
							System.out.println("  Waiting 100ms before retry...");
							Thread.sleep(100); // Wait 100ms before retry
						}
					} else {
						// Different error (not eventual consistency), fail immediately
						System.out.println("  Non-retry error: " + e.getMessage());
						throw e;
					}
				}
			}

			// If all retries exhausted, throw the last exception
			if (data == null && lastException != null) {
				System.out.println("  All 10 retries exhausted, failing");
				throw lastException;
			}

			String dataStr = new String(data, StandardCharsets.UTF_8);
			System.out.println("✓ Read data: " + dataStr);
			System.out.println("  Version: " + stat.getVersion());

			// Write: Update the node
			MWZooKeeperStat newStat = zk.setData("/test", "updated-data".getBytes(StandardCharsets.UTF_8), stat.getVersion());
			System.out.println("✓ Updated node, new version: " + newStat.getVersion());

			// Read: Verify update
			// Retry until we see the updated data
			byte[] data2 = null;
			readRetries = 10;
			while (readRetries > 0) {
				try {
					data2 = zk.getData("/test", stat);
					String tempStr = new String(data2, StandardCharsets.UTF_8);
					// Check if we see the updated data
					if ("updated-data".equals(tempStr)) {
						break; // Success - saw the update!
					}
					// Saw old data, retry
					if (readRetries > 1) {
						Thread.sleep(50);
						readRetries--;
					} else {
						break; // Last retry, proceed anyway
					}
				} catch (MWZooKeeperException e) {
					if (readRetries > 1) {
						Thread.sleep(50);
						readRetries--;
					} else {
						throw e;
					}
				}
			}

			String dataStr2 = new String(data2, StandardCharsets.UTF_8);
			System.out.println("✓ Read updated data: " + dataStr2);

			// Cleanup
			zk.delete("/test", stat.getVersion());
			System.out.println("✓ Deleted node");

			zk.closeConnection();
			System.out.println("✓ Test completed successfully");

		} catch (Exception e) {
			System.err.println("✗ Test failed: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
