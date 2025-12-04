package mw.mapreduce.util;


import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Splitting Text file reader.
 * This helper allows reading text files line-by-line, starting from
 * a given byte postion. It allows for seamless splitting of an input file.
 */
public class MWSplitTextFileInput {

	protected BufferedReader reader;
	protected String encoding;
	protected long bytes_remaining;
	protected boolean full_file;

	/**
	 * Create new reader instance.
	 * <p>
	 * The additional parameters specify the approximate range of bytes to
	 * read from the input file. When start_index is not zero, the reader
	 * will skip input bytes until it finds the next newline character. This
	 * ensures that reading always starts with the next complete line of text.
	 * The skipped bytes also count towards the limit specified in the length
	 * parameter.
	 * <p>
	 * The readLine() function automatically stops after the number of bytes
	 * given in the length parameter was consumed, but still outputs
	 * the last entire line starting within the boundary. This ensures that
	 * seamless splitting is achieved when requesting non-overlapping byte
	 * ranges.
	 *
	 * @param filePath    Input file.
	 * @param start_index Approximate byte offset where to start reading.
	 * @param length      Approximate number of bytes to read.
	 * @throws IOException All I/O exceptions from lower layers are passed on.
	 */
	public MWSplitTextFileInput(String filePath, long start_index, long length) throws IOException {

		// Sanity check on parameters...
		if (length < 0 || length == Long.MAX_VALUE) {
			throw new IndexOutOfBoundsException("Invalid length specified");
		}

		// Create reader
		FileInputStream file_stream = new FileInputStream(filePath);
		if (start_index > 0) {
			file_stream.skip(start_index - 1);
			length = length + 1;
		}
		InputStreamReader stream_reader = new InputStreamReader(file_stream, StandardCharsets.UTF_8);

		// Determine encoding to track consumed bytes. Unfortunately, Java
		// doesn't have a facility that allows tracking byte positions in text
		// streams. We therefore assume that the encoding of a Unicode
		// codepoint is unique, meaning that decoding into an Java String and
		// then encoding it back into a byte stream will yield the original
		// stream again (or at least one with identical length). This is
		// probably true for UTF-8, given we don't encounter any
		// malformed/invalid codes. 
		encoding = stream_reader.getEncoding();
		reader = new BufferedReader(stream_reader);

		bytes_remaining = length;

		// Skip until we find the first complete line of text
		if (start_index > 0) readLine();
	}

	public MWSplitTextFileInput(String filePath) throws IOException {
		reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8));
		full_file = true;
	}

	/**
	 * Read full line of text.
	 *
	 * @return Line of text, or null if outside boundary specified at
	 * construction time.
	 * @throws IOException All I/O exceptions from lower layers are passed on.
	 */
	public String readLine() throws IOException {
		if (!full_file && bytes_remaining <= 0) return null;
		return forceReadLine();
	}

	/**
	 * Force reading line of text.
	 * This function allows continued reading, even if the boundary specified
	 * in the constructor was crossed. Useful for parsing structured text with
	 * information crossing multiple lines.
	 *
	 * @return Line of text, or null if end of file reached.
	 * @throws IOException All I/O exceptions from lower layers are passed on.
	 */
	public String forceReadLine() throws IOException {
		// Reimplementation inspired by BufferedReader.readLine() which allows tracking skipped newline characters.
		StringBuilder s = null;
		while (true) {
			int c = reader.read();
			if (c == -1) {
				break;
			}
			if (s == null) {
				s = new StringBuilder(80);
			}
			// handle '\r\n'
			if (c == '\r') {
				bytes_remaining--;
				reader.mark(1);
				c = reader.read();
				if (c != '\n') reader.reset();
				else bytes_remaining--;
				break;
			} else if (c == '\n') {
				bytes_remaining--;
				break;
			}
			s.append((char) c);
		}

		// Read next line
		String line = (s != null) ? s.toString() : null;
		if (full_file) return line;
		if (line == null) return null;

		// Calculate how much bytes were read for this line
		int bytesRead = line.getBytes(encoding).length;
		bytes_remaining -= bytesRead;

		return line;
	}

	/**
	 * Close stream.
	 *
	 * @throws IOException Possible Exception from underlying stream.
	 */
	public void close() throws IOException {
		reader.close();
	}
}
