package mw.mapreduce.reader;

import mw.mapreduce.util.MWPair;

import java.util.ArrayList;

public class MWKeyValueReader {
    public final String value;
    MWKeyValueReader (String value){
        this.value = value;
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
            String key = line.substring(start, end);
            if (!key.isEmpty()) {
                result.add(new MWPair<>(key, value));
            }
            start = end + 1;

        }
        return result;
    }
}
