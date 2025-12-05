package mw.mapreduce.core;

import mw.mapreduce.reader.MWReader;
import mw.mapreduce.reader.MWReduceReader;
import mw.mapreduce.util.MWPair;

import java.io.*;
import java.util.ArrayList;

public class MWReduceContext implements MWContext<Iterable<String>> {
    private final MWReduceReader reduceReader;
    private final File outputFile;
    private final ArrayList<MWPair<String, String>> results = new ArrayList<>();
    
    public MWReduceContext(MWReduceReader reader, File outputFile) {
        // the reduce reader should use the merging reader internally
        this.reduceReader = reader;
        this.outputFile = outputFile;
    }
    
    @Override
    public MWReader<MWPair<String, Iterable<String>>> getReader() {
        return this.reduceReader;
    }
    
    @Override
    public void write(String key, String value) throws IOException {
        results.add(new MWPair<>(key, value));
    }
    
    @Override
    public void outputComplete() throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (MWPair<String,String> pair : results) {
                writer.write(pair.getKey());
                writer.write('\t');
                writer.write(pair.getValue());
                writer.newLine();
            }
        }
    }
    
}
