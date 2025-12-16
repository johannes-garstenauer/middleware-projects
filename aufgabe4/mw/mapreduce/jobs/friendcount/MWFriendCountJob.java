package mw.mapreduce.jobs.friendcount;

import mw.mapreduce.core.MWJob;
import mw.mapreduce.core.MWMapper;
import mw.mapreduce.core.MWReducer;
import mw.mapreduce.reader.MWKeyValueReader;
import mw.mapreduce.reader.MWLineReader;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;
import mw.mapreduce.util.MWSplitTextFileInput;

import java.io.IOException;

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