package mw.zookeeper;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class MWZooKeeperIdGenerator {
    private final String serverId;
    private AtomicLong counter = new AtomicLong(0);

    public MWZooKeeperIdGenerator(String serverId) {
        Objects.requireNonNull(serverId);
        this.serverId = serverId;
    }

    public String nextUniqueId() {
        return serverId + "-" + counter.incrementAndGet();
    }
}
