package mw.mapreduce.jobs.friendsort;

import mw.mapreduce.core.MWJob;
import mw.mapreduce.core.MWMapper;
import mw.mapreduce.core.MWReducer;
import mw.mapreduce.reader.MWKeyValueReader;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;

public class MWFriendSortJob extends MWJob {

    @Override
    public MWMapper createMapper() {
        return new MWFriendSortMapper();
    }

    public MWReducer createReducer() {
        return new MWFriendSortReducer();
    }

    public MWReader<MWPair<String, String>> createInputReader() throws Exception {
        return new MWKeyValueReader("");
    }

    public java.util.Comparator<String> getComparator() {
        return new MWFriendSortComparator();
    }
}
