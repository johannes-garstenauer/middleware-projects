package mw.mapreduce.reader;

import mw.mapreduce.util.MWPair;

import java.io.IOException;
import java.util.ArrayList;

public class MWKeyValueReader implements MWReader<MWPair<String, String>> {
    private final String value;
    private final String line;
    private int position = 0;

    MWKeyValueReader(String line, String value) {
        this.line = line;
        this.value = value;
    }

    @Override
    public MWPair<String, String> read(){
        int length = line.length();
        // end of line reached
        if (position >= length) {
            return null;
        }
        int start = position;
        int end = line.indexOf('\t', start);
        if (end == -1) {
            end = length;
            position = length;
        } else {
            position = end + 1;
        }
        String key = line.substring(start, end);
        if (key.isEmpty()) {
            // if key is empty, try to read the next token
            return read();
        }
        return new MWPair<>(key, value);
    }

    public ArrayList<MWPair<String, String>> getAllPairs() {
        ArrayList<MWPair<String, String>> result = new ArrayList<>();
        MWPair<String, String> pair;
        while ((pair = read()) != null) {
            result.add(pair);
        }
        return result;
    }
}
