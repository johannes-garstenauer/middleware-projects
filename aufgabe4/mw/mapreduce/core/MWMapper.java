package mw.mapreduce.core;

import java.util.Objects;

import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;

public class MWMapper implements Runnable {
    private MWMapContext context = null;

    public void run() {
        // setContext should be called before with a valid context
        Objects.requireNonNull(context);

        MWReader<MWPair<String, String>> reader = context.getReader();
        MWPair<String, String> pair;
        try {
            while ((pair = reader.read()) != null) {
                map(pair.getKey(), pair.getValue(), context);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setContext(MWMapContext context) {
        this.context = context;
    }

    protected void map(String key, String value, MWContext<?> context) throws Exception {
        context.write(key, value);
    }
}
