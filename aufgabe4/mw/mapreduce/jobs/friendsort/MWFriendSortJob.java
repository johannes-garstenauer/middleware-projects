package mw.mapreduce.jobs.friendsort;

import mw.mapreduce.core.MWJob;
import mw.mapreduce.core.MWMapper;
import mw.mapreduce.core.MWReducer;
import mw.mapreduce.reader.MWKeyValueReader;
import mw.mapreduce.reader.MWLineReader;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;
import mw.mapreduce.util.MWSplitTextFileInput;

import java.io.IOException;

public class MWFriendSortJob extends MWJob {

    @Override
    public MWMapper createMapper() {
        return new MWFriendSortMapper();
    }
    @Override
    public MWReducer createReducer() {
        return new MWFriendSortReducer();
    }

    @Override
    public java.util.Comparator<String> getComparator() {
        return new MWFriendSortComparator();
    }

    @Override
    public MWReader<MWPair<String, String>> createInputReader(String filename, long start, long length) throws Exception {
        MWSplitTextFileInput splitInput = new MWSplitTextFileInput(filename, start, length);

        // Create an anonymous subclass of MWLineReader that delegates to splitInput
        MWLineReader lineReader = new MWLineReader(filename) {
            @Override
            public String read() throws IOException {
                return splitInput.readLine();
            }
        };

        return new MWKeyValueReader(lineReader);
    }
}
