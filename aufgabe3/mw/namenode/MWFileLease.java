package mw.namenode;

import java.io.*;
import java.nio.charset.StandardCharsets;

public record MWFileLease(String leaseID, long expiryTimeMs) {

        /**
         * Serialize lease as:
         *  int leaseIdLen, leaseIdBytes
         *  long expiryMillis
         */
        public byte[] serialize() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(baos)) {
                byte[] idBytes = leaseID.getBytes(StandardCharsets.UTF_8);
                out.writeInt(idBytes.length);
                out.write(idBytes);
                out.writeLong(this.expiryTimeMs);
                out.flush();
                return baos.toByteArray();
            }
        }

        /**
         * Deserialize the format written by serialize().
         */
        public static MWFileLease deserialize(byte[] data) throws IOException {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
                int idLen = in.readInt();
                if (idLen < 0) throw new IOException("Invalid lease id length: " + idLen);
                byte[] idBytes = new byte[idLen];
                in.readFully(idBytes);
                String leaseId = new String(idBytes, StandardCharsets.UTF_8);

                long expiry = in.readLong();
                return new MWFileLease(leaseId, expiry);
            }
        }
}
