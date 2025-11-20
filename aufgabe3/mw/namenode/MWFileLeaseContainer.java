package mw.namenode;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class MWFileLeaseContainer {
    private Map<String, MWFileLease> leases = new HashMap<>();
    private long leaseDurationMs;
    private SecureRandom idSaltGenerator = new SecureRandom();

    public MWFileLeaseContainer(long leaseDurationMs) {
        this.leaseDurationMs = leaseDurationMs;
    }

    /**
     * Retrieve the current lease for the given file or "null" if no active lease exists.
     */
    public MWFileLease getLease(String file) {
        if (!leases.containsKey(file)) {
            // not inside the map, no lease exists
            return null;
        }

        MWFileLease lease = leases.get(file);
        if (lease.expiryTimeMs() < System.currentTimeMillis()) {
            // remove expired lease
            leases.remove(file);

            // lease expired, no lease exists
            return null;
        }

        return lease;
    }

    /**
     * Does this file currently have an active lease?
     */
    public boolean hasActiveLease(String file) {
        return getLease(file) != null;
    }

    /**
     * Add or renew an existing lease.
     */
    public String renewLease(String file) {
        long expiryTimeMs = System.currentTimeMillis() + leaseDurationMs;
        String leaseId = generateLeaseID(file, expiryTimeMs);
        leases.put(file, new MWFileLease(leaseId, expiryTimeMs));

        return leaseId;
    }

    public void removeLease(String file) {
        leases.remove(file);
    }

    private String generateLeaseID(String file, long expiryTimeMs) {
        byte[] salt = new byte[32];
        idSaltGenerator.nextBytes(salt);

        // convert salt bytes to hex string
        StringBuilder saltHex = new StringBuilder(2 * salt.length);
        for (int i = 0; i < salt.length; i++) {
            String hex = Integer.toHexString(0xff & salt[i]);
            if(hex.length() == 1) {
                saltHex.append('0');
            }
            saltHex.append(hex);
        }

        // build unique key
        // this has to contain the file and the time to make it unique
        // additionally a secure salt is used so nobody can guess the lease id
        return file + ":" + expiryTimeMs + ":" + saltHex;
    }

}
