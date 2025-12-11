package mw.mapreduce.core;

import java.util.Comparator;

import mw.mapreduce.reader.MWKeyValueReader;
import mw.mapreduce.reader.MWLineReader;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;

public class MWJob {
    public MWMapper createMapper() {
        return new MWMapper();
    }

    public MWReducer createReducer() {
        return new MWReducer();
    }

    public MWReader<MWPair<String, String>> createInputReader(String filename, long start, long length) throws Exception {
        // default reader, reads nothing
        return new MWKeyValueReader(new MWLineReader(filename));
    }

    public Comparator<String> getComparator() {
        return Comparator.naturalOrder();
    }
}
