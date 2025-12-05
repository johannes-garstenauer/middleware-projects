package mw.mapreduce.core;

import java.io.IOException;

import mw.mapreduce.util.MWPair;
import mw.mapreduce.reader.MWReader;

public interface MWContext<VALUETYPE> {
    // Input
    MWReader<MWPair<String, VALUETYPE>> getReader();
    // Output
    void write(String key, String value) throws IOException;
    void outputComplete() throws IOException;
}
