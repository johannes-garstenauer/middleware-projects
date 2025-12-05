package mw.mapreduce.reader;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class MWLineReader implements MWReader<String> {

    private final BufferedReader reader;

    public MWLineReader(String filename) throws IOException {
        this.reader = new BufferedReader(new FileReader(filename));
    }

    @Override
    public String read() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            reader.close();
        }
        return line;
    }
}