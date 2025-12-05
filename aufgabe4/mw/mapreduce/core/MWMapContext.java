package mw.mapreduce.core;

import mw.mapreduce.reader.MWKeyValueReader;
import mw.mapreduce.reader.MWLineReader;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;

import java.io.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MWMapContext implements MWContext<String>{
    static class MWPairComparator implements Comparator<MWPair<String, String>> {
        private final Comparator<String> comparator;
        public MWPairComparator(Comparator<String> comparator) {
            this.comparator = comparator;
        }
        public int compare(MWPair<String, String> p1, MWPair<String, String> p2) {
            return comparator.compare(p1.getKey(), p2.getKey());
        }
    }

    private final MWKeyValueReader kvreader;
    private final Comparator<String> stringComparator;
    private final Comparator<MWPair<String, String>> pairComparator;
    private final File outputDir;
    private final int numPartitions;
    private final List<List<MWPair<String,String>>> partitions;

    public MWMapContext(Comparator<String> comparator, String inputFile,
                        File outputDir, int numPartitions) throws IOException {
        MWLineReader lineReader = new MWLineReader(inputFile);
        this.kvreader = new MWKeyValueReader(lineReader);
        this.stringComparator = comparator;
        this.outputDir = outputDir;
        this.numPartitions = numPartitions;
        this.partitions = new ArrayList<>(numPartitions);
        for (int i = 0; i < numPartitions; i++) {
            this.partitions.add(new ArrayList<>());
        }
        this.pairComparator = new MWPairComparator(stringComparator);
    }

    @Override
    public MWReader<MWPair<String, String>> getReader() {
        return this.kvreader;
    }

    @Override
    public void write(String key, String value) throws IOException {
        int partitionIndex = getPartition(key);
        partitions.get(partitionIndex).add(new MWPair<>(key, value));
    }
    @Override
    public void outputComplete() throws IOException {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Cannot create output directory: " + outputDir);
        }
        for  (int i = 0; i < partitions.size(); i++) {
            List<MWPair<String, String>> partition = partitions.get(i);
            partition.sort(pairComparator);
            File outputFile = new File(outputDir, "map-out-partition-" + i + ".txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                for (MWPair<String, String> pair : partition) {
                    writer.write(pair.getKey());
                    writer.write('\t');
                    writer.write(pair.getValue());
                    writer.newLine();
                }
            }
        }
    }

    int getPartition(String key) {
        return (key.hashCode() & Integer.MAX_VALUE) % numPartitions;
    }
}
