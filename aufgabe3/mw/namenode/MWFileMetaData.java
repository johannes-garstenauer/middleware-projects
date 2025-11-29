package mw.namenode;

import java.util.ArrayList;
import java.util.List;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record MWFileMetaData(String name, int size, List<MWFileBlock> blocks) {
    public MWFileMetaData {
        // JSON-B will call this one with all 3 params
        if (blocks == null) {
            blocks = new ArrayList<>();
        }
    }

    public void addBlock(MWFileBlock block) {
        blocks.add(block);
    }

    public byte[] serialize() throws IOException {
        Objects.requireNonNull(name, "name");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);

            out.writeInt(size);

            // blocks
            out.writeInt(blocks.size());
            for (MWFileBlock b : blocks) {
                // assume MWFileBlock has blockId() and node() accessors
                String blockId = b.id();
                byte[] blockIdBytes = blockId.getBytes(StandardCharsets.UTF_8);
                out.writeInt(blockIdBytes.length);
                out.write(blockIdBytes);

                MWNodeMetaData node = b.node();
                String host = node.host();
                byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
                out.writeInt(hostBytes.length);
                out.write(hostBytes);

                out.writeInt(node.port());
            }
            out.flush();
            return baos.toByteArray();
        }
    }

    public static MWFileMetaData deserialize(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int nameLen = in.readInt();
            if (nameLen < 0) throw new IOException("Invalid name length in metadata: " + nameLen);
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            int size = in.readInt();

            int blocksCount = in.readInt();
            if (blocksCount < 0) throw new IOException("Invalid blocks count: " + blocksCount);
            List<MWFileBlock> blocks = new ArrayList<>(blocksCount);
            for (int i = 0; i < blocksCount; i++) {
                int blockIdLen = in.readInt();
                if (blockIdLen < 0) throw new IOException("Invalid blockId length: " + blockIdLen);
                byte[] blockIdBytes = new byte[blockIdLen];
                in.readFully(blockIdBytes);
                String blockId = new String(blockIdBytes, StandardCharsets.UTF_8);

                int hostLen = in.readInt();
                if (hostLen < 0) throw new IOException("Invalid host length: " + hostLen);
                byte[] hostBytes = new byte[hostLen];
                in.readFully(hostBytes);
                String host = new String(hostBytes, StandardCharsets.UTF_8);

                int port = in.readInt();

                MWNodeMetaData node = new MWNodeMetaData(host, port);
                MWFileBlock block = new MWFileBlock(blockId, node);
                blocks.add(block);
            }

            return new MWFileMetaData(name, size, blocks);
        }
    }
}
