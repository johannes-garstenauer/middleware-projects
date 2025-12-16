package mw.mapreduce.jobs.friendextract;

import mw.mapreduce.core.MWJob;
import mw.mapreduce.core.MWMapper;
import mw.mapreduce.core.MWReducer;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;

import java.util.Comparator;

public class MWFriendExtractJob extends MWJob {

    @Override
    public MWMapper createMapper() {
        return new MWFriendExtractMapper();
    }

    @Override
    public MWReducer createReducer() {
        return new MWFriendExtractReducer();
    }

    @Override
    public MWReader<MWPair<String, String>> createInputReader(String filename, long start, long length) throws Exception {
        // Hier unseren speziellen Reader verwenden
        return new MWFriendExtractReader(filename, start, length);
    }

    @Override
    public Comparator<String> getComparator() {
        // Standard String-Vergleich reicht
        return Comparator.naturalOrder();
    }
}