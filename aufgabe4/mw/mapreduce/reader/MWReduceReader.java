package mw.mapreduce.reader;

import java.io.IOException;
import java.util.Iterator;

import mw.mapreduce.util.MWPair;


public class MWReduceReader implements MWReader<MWPair<String, Iterable<String>>>, Iterable<String>, Iterator<String> {

	private final MWReader<MWPair<String, String>> input;
	private MWPair<String, String> nextKeyValue;
	private String iteratorKey;

	public MWReduceReader(MWReader<MWPair<String, String>> input) throws IOException {
		this.input = input;
		this.nextKeyValue = input.read();
	}

	@Override
	public MWPair<String, Iterable<String>> read() throws IOException {
		// Jump to the new key if the previous iterator has not read all of its key-value pairs
		if (iteratorKey != null) {
			while (hasNext()) next();
		}

		// Check for input end
		if (nextKeyValue == null) return null;

		// Provide new data
		iteratorKey = nextKeyValue.getKey();
		return new MWPair<String, Iterable<String>>(iteratorKey, this);
	}

	@Override
	public Iterator<String> iterator() {
		return this;
	}

	@Override
	public boolean hasNext() {
		if (nextKeyValue == null) return false;
		return nextKeyValue.getKey().equals(iteratorKey);
	}

	@Override
	public String next() {
		if (!hasNext()) return null;

		// Move to next value
		String value = nextKeyValue.getValue();
		try {
			nextKeyValue = input.read();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
		return value;
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException();
	}
}
