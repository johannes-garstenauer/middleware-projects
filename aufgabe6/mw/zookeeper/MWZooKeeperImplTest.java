package mw.zookeeper;

import java.io.*;

public class MWZooKeeperImplTest {

	public static void main(String[] args) {
		MWZooKeeperImplTest test = new MWZooKeeperImplTest();
		System.out.println("Testing MWZooKeeperImpl, feel free to add own test cases");
		System.out.println("NOTE: The tests only cover a selected subset of MWZooKeeperImpl");
		System.out.println();
		System.out.println("testCreateRead: Check that a node can be read after creating it");
		test.testCreateRead();
		System.out.println("testSimpleSet: Check that setting data twice works as expected");
		test.testSimpleSet();
		System.out.println("testConditionalSet: Test node version check for the setData operation");
		test.testConditionalSet();
		System.out.println("testSetReplication: Test that transactions work after replication");
		test.testSetReplication();
		System.out.println("testPassiveReplication: Test that MWZooKeeperImpl seems to implement passive replication");
		test.testPassiveReplication();
		System.out.println("SUCCESS: Tests completed successfully");
	}

	private MWZooKeeperImpl newImpl() {
		// TODO: adjust constructor invocation if necessary
		return new MWZooKeeperImpl();
	}


	// Short notation of test cases:
	// Process a write <operation> and create transaction <txn> with the given zxid: process(<operation>) = <txn>
	// The suffix '(error)' for <txn> marks an error transaction
	// Set <data> on the node with <path> if <version> matches or <version> == -1: set(<path>,<data>,version=<version>)
	// Apply transaction <txn> to the replica state: apply(<txn>) == <result>
	// Process read request for node <path> with <result>: read(<path>) -> <result>
	// <result> can either be the 'ok' if the node exists or 'err' if the node does not exist
	// The label '<leader/follower>: ' denotes that the following commands would be executed by a specific replica
	void testCreateRead() {
		// process(create(a))=1, read(a) -> err, apply(1), read(a) -> ok
		// Detailed explanation:
		// 1. process a request to create node "a" to generate a transaction and assign it zxid 1
		// 2. try to read node "a". This must fail as the transaction is not yet applied
		// 3. apply the transaction with zxid 1
		// 4. try to read node "a". Now the created node must be returned
		MWZooKeeperImpl state = newImpl();
		String nodePath = "/abcdefg";
		MWZooKeeperResponse readResult = state.processReadRequest(
				new MWZooKeeperRequest(MWZooKeeperOperation.GET_DATA, nodePath));
		assertTrue(isResultException(readResult), "Initial state is not empty");

		MWZooKeeperRequest createRequest = new MWZooKeeperRequest(MWZooKeeperOperation.CREATE, nodePath);
		createRequest.setData(new byte[] {'4', '2'});
		MWZooKeeperTxn createTxn = state.processWriteRequest(createRequest, 1);
		readResult = state.processReadRequest(
				new MWZooKeeperRequest(MWZooKeeperOperation.GET_DATA, nodePath));
		assertTrue(isResultException(readResult), "Read before confirmed create operation must fail");

		MWZooKeeperResponse createResponse = state.applyTxn(createTxn, 1);
		assertNoException(createResponse, "Create request must succeed");

		readResult = state.processReadRequest(
				new MWZooKeeperRequest(MWZooKeeperOperation.GET_DATA, nodePath));
		assertNoException(readResult, "Read after create must succeed");
	}

	void testSimpleSet() {
		// process(set(a,41,version=-1))=1, process(set(a,42,version=-1))=2, apply(1) == ok, apply(2) == ok
		// Detailed explanation:
		// 1. process an unconditional set request for node "a" to "41" and generate a transaction with zxid 1
		// 2. process an unconditional set request for node "a" to "42" and generate a transaction with zxid 2
		// 3. apply the transaction with zxid 1 and check that it succeeded
		// 4. apply the transaction with zxid 2 and check that it succeeded
		String nodePath = "/abcdefg";
		MWZooKeeperImpl state = newImplWithNode(nodePath);
		MWZooKeeperRequest setRequest1 = new MWZooKeeperRequest(MWZooKeeperOperation.SET_DATA, nodePath);
		setRequest1.setData(new byte[] {41});
		MWZooKeeperTxn set1Txn = state.processWriteRequest(setRequest1, 2);

		// The expected node version in the request defaults to -1, which is the wildcard version.
		// That is -1 always matches, independent of the current node version.
		MWZooKeeperRequest setRequest2 = new MWZooKeeperRequest(MWZooKeeperOperation.SET_DATA, nodePath);
		setRequest2.setData(new byte[] {42});
		MWZooKeeperTxn set2Txn = state.processWriteRequest(setRequest2, 3);

		MWZooKeeperResponse set1Response = state.applyTxn(set1Txn, 2);
		assertNoException(set1Response, "Set request must succeed");
		checkData(nodePath, state, (byte)41);

		MWZooKeeperResponse set2Response = state.applyTxn(set2Txn, 3);
		assertNoException(set2Response, "Set request must succeed");
		checkData(nodePath, state, (byte)42);
	}

	void testConditionalSet() {
		// process(set(a,41,version=1))=1, process(set(a,42,version=1))=2, apply(1) == ok, apply(2) == err
		// Detailed explanation:
		// 1. process a conditional set request for node "a" to "41" if version==1 and generate a transaction with zxid 1
		// 2. process a conditional set request for node "a" to "42" if version==1 and generate a transaction with zxid 2
		// 3. apply the transaction with zxid 1 and check that it succeeded
		// 4. apply the transaction with zxid 2 and check that it yields an error, as the version didn't match
		String nodePath = "/abcdefg";
		MWZooKeeperImpl state = newImplWithNode(nodePath);
		MWZooKeeperResponse readResult = state.processReadRequest(
				new MWZooKeeperRequest(MWZooKeeperOperation.GET_DATA, nodePath));
		int version;
		try {
			version = readResult.getStat().getVersion();
		} catch (MWZooKeeperException ignore) {
			throw new AssertionError("Failed to get version for node " + nodePath);
		}
		// Disallow use of wildcard version number -1
		assertTrue(version >= 0,"Node version must be a positive number");

		MWZooKeeperRequest setRequest1 = new MWZooKeeperRequest(MWZooKeeperOperation.SET_DATA, nodePath);
		setRequest1.setVersion(version);
		setRequest1.setData(new byte[] {41});
		MWZooKeeperTxn set1Txn = state.processWriteRequest(setRequest1, 2);

		MWZooKeeperRequest setRequest2 = new MWZooKeeperRequest(MWZooKeeperOperation.SET_DATA, nodePath);
		setRequest2.setVersion(version);
		setRequest2.setData(new byte[] {42});
		MWZooKeeperTxn set2Txn = state.processWriteRequest(setRequest2, 3);

		MWZooKeeperResponse set1Response = state.applyTxn(set1Txn, 2);
		assertNoException(set1Response, "First conditional set request must succeed");
		checkData(nodePath, state, (byte) 41);

		MWZooKeeperResponse set2Response = state.applyTxn(set2Txn, 3);
		assertTrue(isResultException(set2Response), "Second conditional set request must fail");
		checkData(nodePath, state, (byte) 41);
	}

	void testSetReplication() {
		// leader: process(set(a,41,version=-1))=1, process(set(a,42,version=-1))=2
		// follower: apply(1) == ok, apply(2) == ok

		// Detailed explanation:
		// on the leader replica
		// 1. process an unconditional set request for node "a" to "41" and generate a transaction with zxid 1
		// 2. process an unconditional set request for node "a" to "42" and generate a transaction with zxid 2
		// on a follower replica
		// 3. apply the transaction with zxid 1 and check that it succeeded
		// 4. apply the transaction with zxid 2 and check that it succeeded
		String nodePath = "/abcdefg";
		MWZooKeeperImpl leaderState = newImplWithNode(nodePath);

		MWZooKeeperRequest setRequest1 = new MWZooKeeperRequest(MWZooKeeperOperation.SET_DATA, nodePath);
		setRequest1.setData(new byte[] {41});
		MWZooKeeperTxn set1Txn = leaderState.processWriteRequest(setRequest1, 2);

		MWZooKeeperRequest setRequest2 = new MWZooKeeperRequest(MWZooKeeperOperation.SET_DATA, nodePath);
		setRequest2.setData(new byte[] {42});
		MWZooKeeperTxn set2Txn = leaderState.processWriteRequest(setRequest2, 3);

		MWZooKeeperImpl followerState = newImplWithNode(nodePath);
		MWZooKeeperResponse set1Response = followerState.applyTxn(copy(set1Txn), 2);
		assertNoException(set1Response, "Set request must succeed");

		MWZooKeeperResponse set2Response = followerState.applyTxn(copy(set2Txn), 3);
		assertNoException(set2Response, "Set request must succeed");

		checkData(nodePath, followerState, (byte) 42);
	}

	void testPassiveReplication() {
		// This can catch a few variants of active replication
		// Leader a: process(create(a))=1, apply(1), process(create(a))=2(error)
		// Leader b: process(create(b))=1b
		// Follower: apply(1b) == ok, apply(2) == error

		// Detailed explanation:
		// on the leader replica
		// 1. process a request to create node "a" to generate a transaction and assign it zxid 1
		// 2. apply the transaction with zxid 1
		// 3. process a request to create node "a" to generate a transaction and assign it zxid 2.
		//    This the transaction must be an error transaction
		// on a alternative leader replica
		// 4b. process a request to create node "b" to generate a transaction and assign it zxid 1(b)
		// on a follower replica
		// 5. apply the transaction with zxid 1b and check that it succeeded
		// 6. apply the (error) transaction with zxid 2 and check that it returns an error
		String nodePath = "/abcdefg";
		MWZooKeeperImpl leaderState = newImplWithNode(nodePath);

		// This request must fail and create an error transaction
		MWZooKeeperRequest errorRequest = new MWZooKeeperRequest(MWZooKeeperOperation.CREATE, nodePath);
		errorRequest.setData(new byte[] {'4', '2'});
		MWZooKeeperTxn errorTxn = leaderState.processWriteRequest(errorRequest, 2);
		MWZooKeeperResponse errorResponse = leaderState.applyTxn(errorTxn, 2);
		assertTrue(isResultException(errorResponse),"Duplicate create request must fail");
		MWZooKeeperException initialError = getResponseException(errorResponse);

		String nodePathB = "/abcdefgB";
		MWZooKeeperImpl leaderBState = newImpl();
		MWZooKeeperRequest createBRequest = new MWZooKeeperRequest(MWZooKeeperOperation.CREATE, nodePathB);
		createBRequest.setData(new byte[]{'4', '2', 'b'});
		MWZooKeeperTxn createBTxn = leaderBState.processWriteRequest(createBRequest, 1);

		MWZooKeeperImpl followerState = newImpl();
		MWZooKeeperResponse createResponse = followerState.applyTxn(copy(createBTxn), 1);
		assertNoException(createResponse, "Create request must succeed");

		errorResponse = followerState.applyTxn(copy(errorTxn), 2);
		assertTrue(isResultException(errorResponse), "Error checks must already happen at the leader");

		MWZooKeeperException replicatedError = getResponseException(errorResponse);
		assertTrue(initialError != null && replicatedError != null, "Internal error");
		assertTrue(initialError.toString().equals(replicatedError.toString()),
				"Error message changed unexpectedly");
	}


	// Helper methods

	private MWZooKeeperImpl newImplWithNode(String nodePath) {
		MWZooKeeperImpl state = newImpl();
		MWZooKeeperRequest createRequest = new MWZooKeeperRequest(MWZooKeeperOperation.CREATE, nodePath);
		createRequest.setData(new byte[]{'4','2','d','e','f','a','u','l','t'});
		MWZooKeeperTxn createTxn = state.processWriteRequest(createRequest, 1);
		MWZooKeeperResponse createResponse = state.applyTxn(createTxn, 1);
		assertNoException(createResponse, "Create request must succeed");
		return state;
	}

	private void assertTrue(boolean flag, String message) {
		if (!flag) throw new AssertionError(message);
	}

	private void assertNoException(MWZooKeeperResponse result, String message) {
		if (isResultException(result)) {
			throw new AssertionError(message + "\nReturned error: " + getResponseException(result));
		}
	}

	private MWZooKeeperException getResponseException(MWZooKeeperResponse result) {
		try {
			result.getData();
			return null;
		} catch(MWZooKeeperException e) {
			return e;
		}
	}

	private boolean isResultException(MWZooKeeperResponse result) {
		return getResponseException(result) != null;
	}

	private void checkData(String nodePath, MWZooKeeperImpl state, byte data) {
		MWZooKeeperResponse readResult = state.processReadRequest(
				new MWZooKeeperRequest(MWZooKeeperOperation.GET_DATA, nodePath));
		try {
			assertTrue(readResult.getData().length == 1
					&& readResult.getData()[0] == data, "Read wrong data");
		} catch (MWZooKeeperException ignore) {
			throw new AssertionError("Failed to get data for node " + nodePath);
		}
	}

	private MWZooKeeperTxn copy(MWZooKeeperTxn txn) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(baos);
			oos.writeObject(txn);
			oos.close();
			ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
			ObjectInputStream ois = new ObjectInputStream(bais);
			return (MWZooKeeperTxn) ois.readObject();
		} catch (IOException | ClassNotFoundException e) {
			throw new AssertionError("Serialization error: ", e);
		}
	}
}
