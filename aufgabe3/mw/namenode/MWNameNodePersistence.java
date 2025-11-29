package mw.namenode;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Objects;

/**
 * Minimal persistence helper: append-only WAL (length-prefixed records) + snapshot.
 */
public class MWNameNodePersistence {

    public interface PersistenceHandler {
        void restoreSnapshot(InputStream snapshotStream) throws IOException;
        void applyOperation(byte[] op) throws IOException;
    }

    private final Path dir;
    private final Path walFile;
    private final Path snapshotFile;
    private final Path snapshotTmpFile;

    public MWNameNodePersistence(Path dir) throws IOException {
        this.dir = Objects.requireNonNull(dir);
        this.walFile = dir.resolve("namenode.wal");
        this.snapshotFile = dir.resolve("namenode.snap");
        this.snapshotTmpFile = dir.resolve("namenode.snap.tmp");
        Files.createDirectories(dir);
        // ensure files exist
        if (Files.notExists(walFile)) Files.createFile(walFile);
    }

    /**
     * Append a single operation record to the WAL and force it to disk.
     * Record format: 4-byte big-endian length followed by payload bytes.
     */
    public synchronized void appendOp(byte[] op) throws IOException {
        try (FileChannel fc = FileChannel.open(walFile,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            ByteBuffer len = ByteBuffer.allocate(4).putInt(op.length);
            len.flip();
            fc.write(len);
            fc.write(ByteBuffer.wrap(op));
            fc.force(true);
        }
    }

    /**
     * Create an atomic snapshot from snapshotData. Writes to a temp file, forces it,
     * then atomically moves it to the snapshot location. After successful swap the WAL is truncated.
     */
    public synchronized void snapshot(byte[] snapshotData) throws IOException {
        // write temp snapshot
        try (FileChannel fc = FileChannel.open(snapshotTmpFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            fc.write(ByteBuffer.wrap(snapshotData));
            fc.force(true);
        }

        // atomically move into place (replace existing)
        Files.move(snapshotTmpFile, snapshotFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // truncate WAL
        try (FileChannel fc = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
            fc.truncate(0);
            fc.force(true);
        }
    }

    /**
     * Load snapshot (if present) via handler.restoreSnapshot, then replay WAL records calling handler.applyOperation.
     * WAL records are read in the same length-prefixed format as appended by appendOp.
     */
    public synchronized void load(PersistenceHandler handler) throws IOException {
        Objects.requireNonNull(handler);
        if (Files.exists(snapshotFile)) {
            try (InputStream in = Files.newInputStream(snapshotFile, StandardOpenOption.READ)) {
                handler.restoreSnapshot(in);
            }
        }

        if (!Files.exists(walFile)) return;

        try (FileChannel fc = FileChannel.open(walFile, StandardOpenOption.READ)) {
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            while (true) {
                lenBuf.clear();
                int read = fc.read(lenBuf);
                if (read == -1) break;
                while (lenBuf.hasRemaining()) {
                    int r = fc.read(lenBuf);
                    if (r == -1) throw new EOFException("Corrupted WAL (incomplete length)");
                }
                lenBuf.flip();
                int len = lenBuf.getInt();
                if (len < 0) throw new IOException("Invalid WAL record length: " + len);
                ByteBuffer data = ByteBuffer.allocate(len);
                while (data.hasRemaining()) {
                    int r = fc.read(data);
                    if (r == -1) throw new EOFException("Corrupted WAL (incomplete record)");
                }
                handler.applyOperation(data.array());
            }
        }
    }
}
