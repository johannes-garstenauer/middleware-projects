package mw.mapreduce.reader;

import java.io.IOException;


public interface MWReader<RESULT> {

	/**
	 * Read Key-Value pair from input stream.
	 *
	 * @return Key/Value pair or null if end reached.
	 */
	RESULT read() throws IOException;

}
