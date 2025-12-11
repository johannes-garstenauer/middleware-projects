package mw.mapreduce;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import mw.mapreduce.core.MWJob;
import mw.mapreduce.core.MWMapContext;
import mw.mapreduce.core.MWMapper;
import mw.mapreduce.core.MWReduceContext;
import mw.mapreduce.core.MWReducer;
import mw.mapreduce.jobs.friendcount.MWFriendCountJob;
import mw.mapreduce.jobs.friendextract.MWFriendExtractJob;
import mw.mapreduce.jobs.friendsort.MWFriendSortJob;
import mw.mapreduce.reader.MWKeyValueReader;
import mw.mapreduce.reader.MWLineReader;
import mw.mapreduce.reader.MWMergingReader;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.reader.MWReduceReader;
import mw.mapreduce.util.MWPair;

public class MWMapReduce {
    private record MWApp(String name, MWJob job) {}
    private record MWWorkerConfiguration(int mapperWorkers, int reducerWorkers) {}
    private record MWFileConfiguration(String infile, String tmpprefix, String outprefix) {}

    private class KeyComparator implements Comparator<MWPair<String, String>> {
        @Override
        public int compare(MWPair<String, String> arg0, MWPair<String, String> arg1) {
            return arg0.getKey().compareTo(arg1.getKey());
        }
    }
    private final KeyComparator KEY_COMPARATOR = new KeyComparator();


    static final MWApp APPS[] = {
        new MWApp("default", new MWJob()),
        new MWApp("friend-count", new MWFriendCountJob()),
        new MWApp("friend-extract", new MWFriendExtractJob()),
        new MWApp("friend-sort", new MWFriendSortJob())
    };

    private MWApp app;
    private MWFileConfiguration fileConfiguration;
    private MWWorkerConfiguration workerConfiguration;
    private ExecutorService executorService = null;

    private MWMapReduce(MWApp app, MWFileConfiguration fileConfiguration, MWWorkerConfiguration workerConfiguration) {
        this.app = app;
        this.fileConfiguration = fileConfiguration;
        this.workerConfiguration = workerConfiguration;
    }

    private void startMap() throws Exception {
        if (executorService != null) {
            // previous task not finished
            throw new IllegalStateException();
        }
        System.out.println("Starting map tasks...");

        int threads = Math.min(Runtime.getRuntime().availableProcessors(), workerConfiguration.mapperWorkers);
        executorService = Executors.newFixedThreadPool(threads);

        long infileLength = new File(fileConfiguration.infile).length();
    
        for (int i = 0; i < workerConfiguration.mapperWorkers; i++) {
            Comparator<String> comparator = app.job.getComparator();

            File tmpDir = new File(fileConfiguration.tmpprefix + "-" + i);

            long start = (i * infileLength) / workerConfiguration.mapperWorkers;
            long end = Math.min(infileLength, ((i + 1) * infileLength) / workerConfiguration.mapperWorkers);
            long length = end - start;

            MWReader<MWPair<String, String>> reader = app.job.createInputReader(fileConfiguration.infile, start, length);

            MWMapContext mapContext = new MWMapContext(comparator, reader, tmpDir, workerConfiguration.reducerWorkers);

            MWMapper mapper = app.job.createMapper();
            mapper.setContext(mapContext);

            executorService.execute(mapper);
        }
        executorService.close();
    }

    private void startReduce() throws IOException {
        if (executorService != null) {
            // previous task not finished
            throw new IllegalStateException();
        }
        System.out.println("Starting reduce tasks...");

        int threads = Math.min(Runtime.getRuntime().availableProcessors(), workerConfiguration.reducerWorkers);
        this.executorService = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < workerConfiguration.reducerWorkers; i++) {
            MWMergingReader mergingReader = new MWMergingReader(KEY_COMPARATOR);
            for (int j = 0; j < workerConfiguration.mapperWorkers; j++) {
                File tmpDir = new File(fileConfiguration.tmpprefix + "-" + j);
                File tmpFile = new File(tmpDir, "map-out-partition-" + i + ".txt");
                if (!tmpFile.exists()) {
                    throw new FileNotFoundException(tmpFile.getAbsolutePath());
                }

                MWKeyValueReader keyValueReader = new MWKeyValueReader(new MWLineReader(tmpFile.getAbsolutePath()));
                mergingReader.addToQueue(keyValueReader.getAllPairs());
            }

            MWReduceReader reduceReader = new MWReduceReader(mergingReader);

            File outputFile = new File(fileConfiguration.outprefix + "-" + i + ".txt");
            MWReduceContext reduceContext = new MWReduceContext(reduceReader, outputFile);

            MWReducer reducer = app.job.createReducer();
            reducer.setContext(reduceContext);
        
            executorService.execute(reducer);
        }
        executorService.close();
    }

    private void awaitTermination() {
        // generic way to await termination of all scheduled tasks
        while (!executorService.isTerminated()) {
            try {
                if (executorService.awaitTermination(1, TimeUnit.HOURS)) {
                    break;
                }
            } catch (InterruptedException e) {}
        }
        executorService = null;
        System.out.println("All tasks are finished now.");
    }

    private void combineOutput(File file) throws IOException {
        MWMergingReader mergingReader = new MWMergingReader(KEY_COMPARATOR);
        for (int i = 0; i < workerConfiguration.reducerWorkers; i++) {
            File partFile = new File(fileConfiguration.outprefix + "-" + i + ".txt");

            MWKeyValueReader keyValueReader = new MWKeyValueReader(new MWLineReader(partFile.getAbsolutePath()));
            mergingReader.addToQueue(keyValueReader.getAllPairs());
        }

        ArrayList<MWPair<String, String>> mergedList = mergingReader.getMergedList();

        File dir = file.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create output directory: " + dir);
        }
        file.createNewFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (MWPair<String,String> pair : mergedList) {
                writer.write(pair.getKey());
                writer.write('\t');
                writer.write(pair.getValue());
                writer.newLine();
            }
        }

    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("usage mw-map-reduce <app> <infile> <tmpprefix> <outprefix>");
            System.exit(1);
        }

        String appName = args[0];
        String infile = args[1];
        String tmpprefix = args[2];
        String outprefix = args[3];

        MWApp app = Arrays.stream(APPS)
            .filter(a -> a.name.equalsIgnoreCase(appName))
            .findFirst()
            .orElseThrow(() -> {
                System.err.println("App \"" + appName + "\" not found!");
                System.exit(1);

                // return dummy exception, to keep the compiler happy
                return new IllegalStateException();
            });

        MWFileConfiguration fileConfiguration = new MWFileConfiguration(infile, tmpprefix, outprefix);
        MWWorkerConfiguration workerConfiguration = new MWWorkerConfiguration(10, 10);

        MWMapReduce mapReduce = new MWMapReduce(app, fileConfiguration, workerConfiguration);

        File targetFile = new File(fileConfiguration.outprefix + "-combined.txt");

        try {
            mapReduce.startMap();
            mapReduce.awaitTermination();
            mapReduce.startReduce();
            mapReduce.awaitTermination();
            mapReduce.combineOutput(targetFile);
        } catch (Exception e) {
            System.err.println("Exception occurred during map reduce: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("Finished map-reduce.");
        System.out.println("Saved combined results to: " + targetFile.getAbsolutePath());
    }
}
