package mw.mapreduce;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileHasher {
    public static String hashFile(File file) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");

        byte[] buffer = new byte[4 * 1024];
        handleFileRecursively(file, digest, buffer);

        byte[] hash = digest.digest();
        return toHex(hash);
    }

    private static void handleFileRecursively(File file, MessageDigest digest, byte[] buffer) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                handleFileRecursively(child, digest, buffer);
            }
        } else if (file.isFile()) {
            hashFile(file, digest, buffer);
        }
    }

    private static void hashFile(File file, MessageDigest digest, byte[] buffer) {
        FileInputStream reader;
        try {
            reader = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            // should not happen
            throw new RuntimeException(e);
        }

        int size;
        try {
            while ((size = reader.read(buffer)) > 0) {
                digest.update(buffer, 0, size);                
            }
        } catch (IOException e) {}
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(2 * bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            String curr = Integer.toHexString(0xff & bytes[i]);
            if(curr.length() == 1) {
                hex.append('0');
            }
            hex.append(curr);
        }
        return hex.toString();
    }
}
