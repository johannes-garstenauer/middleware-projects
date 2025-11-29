package mw.namenode;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class MWFileLeaseContainer {
    private Map<String, MWFileLease> leases = new HashMap<>();
    private MWUniqueIdGenerator idGenerator = new MWUniqueIdGenerator();
    private long leaseDurationMs;

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
        String leaseId = idGenerator.generateUniqueId(file);
        leases.put(file, new MWFileLease(leaseId, expiryTimeMs));

        return leaseId;
    }

    public void removeLease(String file) {
        leases.remove(file);
    }

    /**
     * Return a shallow copy of current leases for snapshotting.
     */
    synchronized Map<String, MWFileLease> getAllLeasesSnapshot() {
        return new HashMap<>(leases);
    }

    /**
     * Restore an exact lease (used during snapshot/wal replay).
     * Inserts the lease as-is (no new id generation). If the lease is already expired,
     * it will NOT be inserted.
     */
    synchronized void restoreLease(String file, MWFileLease lease) {
        Objects.requireNonNull(file);
        Objects.requireNonNull(lease);
        if (lease.expiryTimeMs() > System.currentTimeMillis()) {
            leases.put(file, lease);
        } else {
            leases.remove(file);
        }
    }

}
