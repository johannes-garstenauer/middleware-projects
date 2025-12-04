package mw.mapreduce.jobs.friendcount;

import mw.mapreduce.core.MWJob;
import mw.mapreduce.core.MWMapper;
import mw.mapreduce.core.MWReducer;
import mw.mapreduce.reader.MWKeyValueReader;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;

public class MWFriendCountJob extends MWJob {

    @Override
    public MWMapper createMapper() {
        return new MWFriendCountMapper();
    }

    @Override
    public MWReducer createReducer() {
        return new MWFriendCountReducer();
    }

    @Override
    public MWReader<MWPair<String, String>> createInputReader(String filename, long start, long length) throws Exception {
        // Ganz normaler KeyValueReader, der bei Tab trennt
        return new MWKeyValueReader(filename, start, length);
    }
}