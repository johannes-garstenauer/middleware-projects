package mw.mapreduce.reader;

import mw.mapreduce.util.MWPair;

import java.io.IOException;
import java.util.ArrayList;

public class MWKeyValueReader implements MWReader<MWPair<String, String>> {

    private final MWLineReader lineReader;
    private int lineNumber = 0;  // 1-based line counter

    public MWKeyValueReader(MWLineReader lineReader) {
        this.lineReader = lineReader;
    }

    @Override
    public MWPair<String, String> read() throws IOException {
            String line = lineReader.read();
            if (line == null) {
                return null;
            }
            lineNumber++;
            int len = line.length();
            int i = 0;
            while (i < len && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= len) {
                String key = Integer.toString(lineNumber);
                String value = line; // or "" if you prefer
                return new MWPair<>(key, value);
            }
            int startKey = i;
            while (i < len && !Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            String key = line.substring(startKey, i);
            while (i < len && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            String value = (i < len) ? line.substring(i) : "";

            return new MWPair<>(key, value);
    }

    public ArrayList<MWPair<String, String>> getAllPairs() throws IOException {
        ArrayList<MWPair<String, String>> result = new ArrayList<>();
        MWPair<String, String> pair;
        while ((pair = read()) != null) {
            result.add(pair);
        }
        return result;
    }
}