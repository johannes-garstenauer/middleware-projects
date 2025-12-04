package mw.mapreduce.reader;

import mw.mapreduce.util.MWPair;

import java.util.ArrayList;

public class MWKeyValueReader {
    public final String key;
    MWKeyValueReader ( String key ){
        this.key = key;
    }

    ArrayList<MWPair<String, String>> getPairs(String line) {
        ArrayList<MWPair<String, String>> result = new  ArrayList<MWPair<String, String>>();
        boolean endOfLine = false;
        int start = 0;
        while (start < line.length()) {
            int end = line.indexOf("\t", start);
            if (end == -1) {
                end = line.length();
            }
            String value = line.substring(start, end);
            if (!value.isEmpty()) {
                result.add(new MWPair<>(key, value));
            }
            start = end + 1;

        }
        return result;
    }
}
