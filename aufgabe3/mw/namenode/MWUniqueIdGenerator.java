package mw.namenode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class MWUniqueIdGenerator {
    private SecureRandom idSaltGenerator = new SecureRandom();

    public String generateUniqueId(String objectName) {
        long currentTimeMs = System.currentTimeMillis();
        long currentTimeNs = System.nanoTime();

        byte[] salt = new byte[32];
        idSaltGenerator.nextBytes(salt);

        // convert salt bytes to hex string
        String saltHex = toHex(salt);

        // build unique id
        // this has to contain the file and the time to make it unique
        // additionally a secure salt is used so nobody can guess the lease id
        String idString = objectName + ":" + currentTimeMs + ":" + currentTimeNs + ":" + saltHex;
    
        // to make id look more natural we hash it
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(idString.getBytes());

            byte[] hash = digest.digest();
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e.getMessage());
        }
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
