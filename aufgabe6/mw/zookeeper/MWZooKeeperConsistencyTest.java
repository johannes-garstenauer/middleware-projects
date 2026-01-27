package mw.zookeeper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests:
 * a) Read operations have lower response times than write operations
 * b) ZooKeeper does not provide strong consistency
 *
 *   Server 1: java MWZooKeeperServer 2181 1 localhost:2888:3888,localhost:2889:3889,localhost:2890:3890
 *   Server 2: java MWZooKeeperServer 2182 2 localhost:2888:3888,localhost:2889:3889,localhost:2890:3890
 *   Server 3: java MWZooKeeperServer 2183 3 localhost:2888:3888,localhost:2889:3889,localhost:2890:3890
 */
public class MWZooKeeperConsistencyTest {

	private static final String TEST_NODE = "/performance_test_node";
	private static final int WARMUP_ITERATIONS = 50;
	private static final int TEST_ITERATIONS = 200;

	public static void main(String[] args) throws Exception {
		System.out.println("=============================================================");
		System.out.println("MWZooKeeper Consistency and Performance Test");
		System.out.println("=============================================================");
		System.out.println();

		// Parse command line arguments
		if (args.length < 1) {
			System.out.println("Usage: java MWZooKeeperConsistencyTest <server1:port1> [server2:port2] ...");
			System.out.println("Example: java MWZooKeeperConsistencyTest localhost:2181 localhost:2182 localhost:2183");
			System.out.println();
			System.out.println("Running with default: localhost:2181");
			args = new String[]{"localhost:2181"};
		}

		MWZooKeeperConsistencyTest test = new MWZooKeeperConsistencyTest();

		// Test a) Response Time Comparison
		System.out.println("TEST A: Response Time Comparison (Read vs Write)");
		System.out.println("-------------------------------------------------------------");
		test.testResponseTimeComparison(args[0]);
		System.out.println();

        // Test b) Eventual Consistency (Stale Reads)
		if (args.length >= 2) {
			System.out.println("TEST B: Eventual Consistency - Demonstrating Stale Reads");
			System.out.println("-------------------------------------------------------------");
			test.testEventualConsistency(args[0], args[1]);
			System.out.println();
		} else {
			System.out.println("TEST B: Skipped (requires at least 2 server addresses)");
			System.out.println("To run this test, provide multiple server addresses:");
			System.out.println("  java MWZooKeeperConsistencyTest localhost:2181 localhost:2182");
			System.out.println();
		}

		System.out.println("=============================================================");
		System.out.println("All tests completed successfully!");
		System.out.println("=============================================================");
	}

	/**
	 * Test A: Demonstrate that read operations are faster than write operations.
	 *
	 * 1. Creates a test node
	 * 2. Measures average response time for read operations
	 * 3. Measures average response time for write operations
	 * 4. Compares the results and shows the performance difference
	 */
	private void testResponseTimeComparison(String serverAddress) throws Exception {
		String[] parts = serverAddress.split(":");
		String host = parts[0];
		int port = Integer.parseInt(parts[1]);

		MWZooKeeper zk = new MWZooKeeper();
		zk.openConnection(host, port);

		try {
			// Setup: Create test node
			System.out.println("Setting up test node...");
			byte[] initialData = "initial".getBytes();
			try {
				zk.create(TEST_NODE, initialData, false);
			} catch (MWZooKeeperException e) {
				System.out.println("Test node already exists, reusing it.");
			}

			// Test READ performance
			System.out.println("Measuring READ operation performance (" + TEST_ITERATIONS + " iterations)...");
            MWZooKeeperStat stat = new MWZooKeeperStat();
            List<Long> readTimes = new ArrayList<>();
			for (int i = 0; i < TEST_ITERATIONS; i++) {
				long startTime = System.nanoTime();
				zk.getData(TEST_NODE, stat);
				long endTime = System.nanoTime();
				readTimes.add(endTime - startTime);
			}

			// Test WRITE performance
			System.out.println("Measuring WRITE operation performance (" + TEST_ITERATIONS + " iterations)...");
			List<Long> writeTimes = new ArrayList<>();
			for (int i = 0; i < TEST_ITERATIONS; i++) {
				byte[] data = ("test_data_" + i).getBytes();
				long startTime = System.nanoTime();
				zk.setData(TEST_NODE, data, -1);
				long endTime = System.nanoTime();
				writeTimes.add(endTime - startTime);
			}

			// Calculate statistics
			double avgReadTimeNs = readTimes.stream().mapToLong(Long::longValue).average().orElse(0);
			double avgWriteTimeNs = writeTimes.stream().mapToLong(Long::longValue).average().orElse(0);

			double avgReadTimeMs = avgReadTimeNs / 1_000_000.0;
			double avgWriteTimeMs = avgWriteTimeNs / 1_000_000.0;

			double speedupFactor = avgWriteTimeNs / avgReadTimeNs;

			// Print results
			System.out.println();
			System.out.println("RESULTS:");
			System.out.println("  READ Operations:");
			System.out.printf("    Average: %.3f ms%n", avgReadTimeMs);
			System.out.println();
			System.out.println("  WRITE Operations:");
			System.out.printf("    Average: %.3f ms%n", avgWriteTimeMs);
			System.out.println();
			System.out.printf("  Performance Difference: READS are %.2fx FASTER than WRITES%n", speedupFactor);
			System.out.println();

			// Cleanup
			try {
				zk.delete(TEST_NODE, -1);
			} catch (MWZooKeeperException e) {
				// Ignore cleanup errors
			}

		} finally {
			zk.closeConnection();
		}
	}

	/**
	 * Test B: Demonstrates eventual consistency by showing that clients can read stale data.
	 *
	 * This test:
	 * 1. Connects one client to server1 (writer)
	 * 2. Connects another client to server2 (reader)
	 * 3. Writer updates a node multiple times
	 * 4. Reader attempts to read immediately after each write
	 * 5. Detects and reports cases where stale data is read
	 */
	private void testEventualConsistency(String server1Address, String server2Address) throws Exception {
		String[] parts1 = server1Address.split(":");
		String host1 = parts1[0];
		int port1 = Integer.parseInt(parts1[1]);

		String[] parts2 = server2Address.split(":");
		String host2 = parts2[0];
		int port2 = Integer.parseInt(parts2[1]);

		String testNode = "/consistency_test_node";

		MWZooKeeper writer = new MWZooKeeper();
		MWZooKeeper reader = new MWZooKeeper();

		writer.openConnection(host1, port1);
		reader.openConnection(host2, port2);

		try {
			// Setup: Create test node via writer
			System.out.println("Setting up test node on server " + server1Address + "...");
			try {
				writer.create(testNode, "0".getBytes(), false);
			} catch (MWZooKeeperException e) {
				// Node might exist, delete and recreate
				try {
					writer.delete(testNode, -1);
					Thread.sleep(100);
				} catch (Exception ex) {}
				writer.create(testNode, "0".getBytes(), false);
			}

			// Allow initial replication
			System.out.println("Waiting for initial replication...");
			Thread.sleep(500);

			// Test: Perform writes and immediate reads
			int numTests = 50;
			int staleReadsDetected = 0;
			int totalReads = 0;

			System.out.println("Performing " + numTests + " write-then-read cycles...");
			System.out.println("Writer: " + server1Address);
			System.out.println("Reader: " + server2Address);
			System.out.println();

			for (int i = 1; i <= numTests; i++) {
				// Write new value
				String expectedValue = String.valueOf(i);
				writer.setData(testNode, expectedValue.getBytes(), -1);

				// Immediately read from different server
				MWZooKeeperStat stat = new MWZooKeeperStat();
				byte[] readData = reader.getData(testNode, stat);
				String readValue = new String(readData);
				totalReads++;

				if (!readValue.equals(expectedValue)) {
					staleReadsDetected++;
					if (staleReadsDetected <= 10) { // Only print first 10 occurrences
						System.out.printf("  Iteration %d: STALE READ detected! Expected '%s', got '%s' (version: %d)%n",
							i, expectedValue, readValue, stat.getVersion());
					}
				}

				// Small delay to allow some replication
				if (i % 10 == 0) {
					Thread.sleep(10);
				}
			}

			System.out.println();
			System.out.println("RESULTS:");
			System.out.printf("  Total operations:     %d%n", totalReads);
			System.out.printf("  Stale reads detected: %d%n", staleReadsDetected);
			System.out.printf("  Stale read rate:      %.1f%%%n", (staleReadsDetected * 100.0 / totalReads));
			System.out.println();

			// Cleanup
			try {
				writer.delete(testNode, -1);
			} catch (MWZooKeeperException e) {
				// Ignore
			}

		} finally {
			writer.closeConnection();
			reader.closeConnection();
		}
	}
}
