package mw.mapreduce.core;

import java.util.Objects;

import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;

public class MWReducer implements Runnable {
    private MWReduceContext context = null;

    public void run() {
        // setContext should be called before with a valid context
        Objects.requireNonNull(context);

        MWReader<MWPair<String, Iterable<String>>> reader = context.getReader();
        MWPair<String, Iterable<String>> pair;
        try {
            while ((pair = reader.read()) != null) {
                reduce(pair.getKey(), pair.getValue(), context);
            }
            context.outputComplete();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setContext(MWReduceContext context) {
        this.context = context;
    }

    protected void reduce(String key, Iterable<String> values,
            MWContext<?> context) throws Exception {

        for (String value : values) {
            context.write(key, value);
        }
    }
}
