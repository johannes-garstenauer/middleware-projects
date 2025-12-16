package mw.mapreduce;

import java.io.File;
import java.security.NoSuchAlgorithmException;

public class FileHashingMiddleware implements Runnable {
    private Runnable runnable;
    private File file;

    public FileHashingMiddleware(Runnable runnable, File file) {
        this.runnable = runnable;
        this.file = file;
    }

    @Override
    public void run() {
        this.runnable.run();

        // calculate and print hash of file content(s)
        try {
            String hex = FileHasher.hashFile(file);
            System.out.println("> Hash for \"" + file.getAbsolutePath() + "\": " + hex);
        } catch (NoSuchAlgorithmException e) {
            // hash algorithm not found
            // ignoring...
            System.out.println("> Hash for \"" + file.getAbsolutePath() + "\": [MD5 not found]");
        }
    }
}
