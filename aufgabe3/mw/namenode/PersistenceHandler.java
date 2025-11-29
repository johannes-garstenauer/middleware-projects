package mw.namenode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Concrete PersistenceHandler that uses MWFileMetaData.deserialize to restore files (including blocks),
 * and handles leases. Snapshot format:
 *   int datanodeCount
 *     for each datanode: int hostLen, hostBytes, int port
 *   int filesCount
 *     for each file: int nameLen, nameBytes, int metaLen, metaBytes (meta == MWFileMetaData.serialize())
 *   int leasesCount
 *     for each lease: int nameLen, nameBytes, int leaseLen, leaseBytes (lease == MWFileLease.serialize())
 *
 * WAL op format:
 *  byte opCode (1=create/update, 2=delete, 3=lease)
 *  opCode 1: int nameLen, nameBytes, int metaLen, metaBytes
 *  opCode 2: int nameLen, nameBytes
 *  opCode 3: byte action (0=remove,1=put), int nameLen,nameBytes, [ if put: int leaseLen, leaseBytes ]
 */
public class PersistenceHandler implements MWNameNodePersistence.PersistenceHandler {

    private final MWNameNode nameNode;

    public PersistenceHandler(MWNameNode nameNode) {
        this.nameNode = Objects.requireNonNull(nameNode);
    }

    @Override
    public void restoreSnapshot(InputStream snapshotStream) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(snapshotStream));

        int datanodeCount;
        try {
            datanodeCount = in.readInt();
        } catch (EOFException e) {
            // empty snapshot
            return;
        }
        // read datanodes first
        List<MWNodeMetaData> nodes = new ArrayList<>(Math.max(0, datanodeCount));
        for (int i = 0; i < datanodeCount; i++) {
            int hostLen = in.readInt();
            if (hostLen < 0) throw new IOException("Invalid host length in snapshot datanode: " + hostLen);
            byte[] hostBytes = new byte[hostLen];
            in.readFully(hostBytes);
            String host = new String(hostBytes, StandardCharsets.UTF_8);

            int port = in.readInt();
            nodes.add(new MWNodeMetaData(host, port));
        }

        // restore datanodes into nameNode
        nameNode.persistenceClearDataNodes();
        nameNode.persistenceSetDataNodes(nodes);



        int numEntries;
        try {
            numEntries = in.readInt();
        } catch (EOFException e) {
            // empty snapshot
            return;
        }

        nameNode.persistenceClearFiles();

        for (int i = 0; i < numEntries; i++) {
            int nameLen = in.readInt();
            if (nameLen < 0) throw new IOException("Invalid name length in snapshot: " + nameLen);
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            int metaLen = in.readInt();
            if (metaLen < 0) throw new IOException("Invalid meta length in snapshot: " + metaLen);
            byte[] meta = new byte[metaLen];
            in.readFully(meta);

            applySnapshotFile(name, meta);
        }

        int leasesCount = in.readInt();
        if (leasesCount < 0) throw new IOException("Invalid leases count: " + leasesCount);
        for (int i = 0; i < leasesCount; i++) {
            int nameLen = in.readInt();
            if (nameLen < 0) throw new IOException("Invalid name length in snapshot leases: " + nameLen);
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            int leaseLen = in.readInt();
            if (leaseLen < 0) throw new IOException("Invalid lease length in snapshot: " + leaseLen);
            byte[] leaseBytes = new byte[leaseLen];
            in.readFully(leaseBytes);

            applyRestoreLease(name, leaseBytes);
        }
    }

    @Override
    public void applyOperation(byte[] op) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(op));
        int opCode;
        try {
            opCode = in.readUnsignedByte();
        } catch (EOFException e) {
            throw new IOException("Empty WAL record", e);
        }

        switch (opCode) {
            case 1: { // CREATE_OR_UPDATE
                int nameLen = in.readInt();
                if (nameLen < 0) throw new IOException("Invalid name length in WAL create/update: " + nameLen);
                byte[] nameBytes = new byte[nameLen];
                in.readFully(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);

                int metaLen = in.readInt();
                if (metaLen < 0) throw new IOException("Invalid meta length in WAL create/update: " + metaLen);
                byte[] meta = new byte[metaLen];
                in.readFully(meta);

                applyCreateOrUpdate(name, meta);
                break;
            }
            case 2: { // DELETE
                int nameLen = in.readInt();
                if (nameLen < 0) throw new IOException("Invalid name length in WAL delete: " + nameLen);
                byte[] nameBytes = new byte[nameLen];
                in.readFully(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);

                applyDelete(name);
                break;
            }
            case 3: { // LEASE restore
                int action = in.readUnsignedByte(); // 0=remove,1=put
                int nameLen = in.readInt();
                if (nameLen < 0) throw new IOException("Invalid name length in WAL lease: " + nameLen);
                byte[] nameBytes = new byte[nameLen];
                in.readFully(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);

                if (action == 1) {
                    int leaseLen = in.readInt();
                    if (leaseLen < 0) throw new IOException("Invalid lease length in WAL lease put: " + leaseLen);
                    byte[] leaseBytes = new byte[leaseLen];
                    in.readFully(leaseBytes);
                    applyRestoreLease(name, leaseBytes);
                } else if (action == 0) {
                    applyDeleteLease(name);
                } else {
                    throw new IOException("Unknown lease action in WAL: " + action);
                }
                break;
            }
            default:
                throw new IOException("Unknown WAL op code: " + opCode);
        }
    }

    private void applySnapshotFile(String name, byte[] meta) throws IOException {
        MWFileMetaData md = MWFileMetaData.deserialize(meta);
        nameNode.persistencePutFile(name, md);
    }

    private void applyCreateOrUpdate(String name, byte[] meta) throws IOException {
        MWFileMetaData md = MWFileMetaData.deserialize(meta);
        nameNode.persistencePutFile(name, md);
    }

    private void applyDelete(String name) throws IOException {
        nameNode.persistenceRemoveFile(name);
    }

    private void applyRestoreLease(String name, byte[] leaseBytes) throws IOException {
        MWFileLease lease = MWFileLease.deserialize(leaseBytes);
        nameNode.persistenceRestoreLease(name, lease);
    }

    private void applyDeleteLease(String name) throws IOException {
        nameNode.persistenceRemoveLease(name);
    }
}
