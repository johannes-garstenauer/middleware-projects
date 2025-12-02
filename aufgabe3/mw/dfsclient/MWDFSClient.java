package mw.dfsclient;

import mw.namenode.MWFileBlock;
import mw.namenode.MWFileMetaData;
import mw.namenode.MWNameNode;
import mw.namenode.MWNodeMetaData;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.client.Entity;



public class MWDFSClient {
    // JAX-RS HTTP Client to reach namenode specified at command line
    private final WebTarget namenode;
    private static final int BLOCKSIZE = 1024*1024;

    public MWDFSClient(String namenode_str) {
        Client client = ClientBuilder.newClient();
        URI baseUri = URI.create("http://"+namenode_str);
        this.namenode = client.target(baseUri).path("namenode");
    }


    // ##############################
    // # LOW-LEVEL PROTOCOL HELPERS #
    // ##############################

    private void uploadBlock(byte[] block, WebTarget datanode, String blockId) throws MWWebServiceException {
        validateBlockSize(block);
        try (Response uploadResponse = datanode.path(blockId).request()
                .post(Entity.entity(block, MediaType.APPLICATION_OCTET_STREAM_TYPE))) {
            if (uploadResponse.getStatus() < 200 || uploadResponse.getStatus() >= 300) {
                throw new MWWebServiceException("Failed to upload block " +
                        ": HTTP " + uploadResponse.getStatus());
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
            throw new MWWebServiceException("Failed to upload block ", e);
        }
    }

    private byte[] downloadBlock(WebTarget datanode) throws MWWebServiceException {
        byte[] block = null;
        try {
            block = datanode.request().get(byte[].class);
        } catch  (Exception e) {
            throw new MWWebServiceException("Failed to download block from datanode " + datanode, e);
        }
        return block;
    }



    private static void validateBlockSize(byte[] block) throws MWWebServiceException {
        if (block.length > 1024*1024) { // 1MiB
            throw new MWWebServiceException("Block size too large!");
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);

            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }


    // ###########################
    // # COMMAND IMPLEMENTATIONS #
    // ###########################

    private List<MWFileMetaData> listFiles() throws MWWebServiceException {
        Response response = namenode.request(MediaType.APPLICATION_JSON).get();
        if (response.getStatus() != 200) {
            throw new MWWebServiceException(response.getStatus() + ": " + response.readEntity(String.class));
        }
        List<MWFileMetaData> files = response.readEntity(
                new GenericType<List<MWFileMetaData>>() {}
        );
        for  (MWFileMetaData file : files) {
            System.out.println(file.name() + " (" + file.size() + " bytes)");
        }
        return files;
    }

    private static final long LEASE_RENEW_INTERVAL_MS = 10 * 60 * 1000 / 2; // from MWNameNode.java

    private String renewLease(String filename, String leaseId) throws MWWebServiceException {
        try (Response renewResponse = namenode
                .path(filename)
                .path("lock")
                .queryParam("renewLease", leaseId)
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.text(""))) {

            int status = renewResponse.getStatus();

            if (status == 200) {
                String newLeaseId = renewResponse.readEntity(String.class);
                return newLeaseId;
            }

            String body;
            try {
                body = renewResponse.readEntity(String.class);
            } catch (IllegalStateException ex) {
                body = "<no body>";
            }

            if (status == 404) {
                throw new MWWebServiceException(
                        "Failed to renew lease for " + filename + ": lease expired or does not exist (404). " + body);
            } else if (status == 403) {
                throw new MWWebServiceException(
                        "Failed to renew lease for " + filename + ": wrong lease ID (403). " + body);
            } else {
                throw new MWWebServiceException(
                        "Failed to renew lease for " + filename + ": "
                                + status + " " + body);
            }
        }
    }

    private void uploadFile(String sPath, int replicas) throws MWWebServiceException {
        Path localPath = Paths.get(sPath);
        File f = new File(sPath);
        String filename = f.getName();
        byte[] content;
        try {
            content = Files.readAllBytes(localPath);
        } catch (IOException e) {
            System.err.println("Failed to read input file!");
            throw new MWWebServiceException("Failed to read input file!", e);
        }

        //check if file already present on the server
        List<MWFileMetaData> files = listFiles();
        List<String> filenames = files.stream()
                .map(MWFileMetaData::name)
                .toList();
        if (filenames.contains(filename)) {
            String downloadPath = "./downloads/" + filename;

            // download server version
            downloadFile(filename, downloadPath);

            File serverFile = new File(downloadPath);
            byte[] serverContent;
            try {
                serverContent = Files.readAllBytes(serverFile.toPath());
            } catch (IOException e) {
                System.err.println("Failed to read downloaded server file!");
                throw new MWWebServiceException("Failed to read downloaded server file!", e);
            }

            String localSha = sha256Hex(content);
            String serverSha = sha256Hex(serverContent);

            if (localSha.equals(serverSha)) {
                System.out.println("File '" + filename + "' already exists on the server with identical content.");
                System.out.println("SHA-256: " + localSha);
                return; // skip upload
            } else {
                System.out.println("File '" + filename + "' exists on the server but contents differ.");
            }
        }

        // get lease
        System.out.println("DEBUG: Acquiring lease");
        String leaseId = null;
        try {
            Client client = ClientBuilder.newClient();
            try (Response lockResponse = namenode.path(filename).path("lock")
                    .request().post(Entity.text(""));) {
                if (lockResponse.getStatus() != 200) {
                    String body;
                    try {
                        body = lockResponse.readEntity(String.class);
                    } catch (IllegalArgumentException e) {
                        body = "<no body>";
                    }
                    throw new MWWebServiceException("Failed to aqcuire lease for "
                            + localPath + ": " + lockResponse.getStatus() + " " + body);
                }
                leaseId = lockResponse.readEntity(String.class);
            }

            System.out.println("DEBUG: UPLOAD BLOCKS");
            // upload blocks
            ArrayList<MWFileBlock> blocks = new ArrayList<MWFileBlock>();
            long nextLeaseRenew = System.currentTimeMillis() + LEASE_RENEW_INTERVAL_MS;
            for (int offset = 0; offset < content.length; offset += BLOCKSIZE) {
                long now = System.currentTimeMillis();
                if (now >= nextLeaseRenew) {
                    leaseId = renewLease(filename, leaseId);
                    nextLeaseRenew = now + LEASE_RENEW_INTERVAL_MS;
                }
                int end = Math.min(content.length, offset + BLOCKSIZE);
                byte[] block = Arrays.copyOfRange(content, offset, end);
                MWFileBlock fileBlock;
                try (Response allocResponse = namenode
                        .path(filename)
                        .path("alloc")
                        //.queryParam("replicas", replicas) // TODO: replicas parameter is currently unused in MWNameNode
                        .request(MediaType.APPLICATION_JSON)
                        .post(Entity.text(""))) {
                    if (allocResponse.getStatus() != 200) {
                        String body;
                        try {
                            body = allocResponse.readEntity(String.class);
                        } catch (IllegalStateException ex) {
                            body = "<no body>";
                        }
                        throw new MWWebServiceException(
                                "Failed to allocate block for " + sPath + ": "
                                        + allocResponse.getStatus() + " " + body);
                    }

                    fileBlock = allocResponse.readEntity(MWFileBlock.class);
                    blocks.add(fileBlock);
                }
                MWFileMetaData metaData = new MWFileMetaData(filename, (int) f.length(), blocks);
                List<MWNodeMetaData> nodeMetaData = fileBlock.nodes();
                String blockId = fileBlock.id();
                for (MWNodeMetaData node : nodeMetaData) {
                    WebTarget datanode = client
                            .target("http://" + node.host()
                                    + ":" + node.port())
                            .path("datablock");
                    uploadBlock(block, datanode, blockId);
                }

                // commit metadata
                System.out.println("DEBUG: Committing block " + blockId);
                try (Response commitResponse = namenode.path(filename)
                        .path("commit")
                        .queryParam("leaseId", leaseId)
                        .request(MediaType.APPLICATION_JSON)
                        .post(Entity.entity(metaData, MediaType.APPLICATION_JSON))) {
                    if (commitResponse.getStatus() != 200) {
                        String body;
                        try {
                            body = commitResponse.readEntity(String.class);
                        } catch (IllegalStateException ex) {
                            body = "<no body>";
                        }
                        throw new MWWebServiceException("Failed to commit block "
                                + blockId + " for " + filename + ": "
                                + commitResponse.getStatus() + " " + body);
                    }
                }
            }
        } catch (MWWebServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new MWWebServiceException("Unexpected error while uploading" + filename, e);
        } finally {
            if (leaseId != null) {
                try (Response unlockResponse = namenode
                        .path(sPath)
                        .path("unlock")
                        .queryParam("leaseId", leaseId)
                        .request()
                        .post(Entity.text(""))) {
                    if (unlockResponse.getStatus() != 200) {
                        String body;
                        try {
                            body = unlockResponse.readEntity(String.class);
                        } catch (IllegalStateException ex) {
                            body = "<no body>";
                        }
                        System.err.println(
                                "Failed to unlock " + sPath + " with leaseId " + leaseId + ": "
                                        + unlockResponse.getStatus() + " " + body);
                    }
                } catch (Exception e) {
                    System.err.println(
                            "Exception while trying to unlock " + sPath
                                    + " with leaseId " + leaseId + ": "
                                    + e.getMessage());
                }
            }
        }
    }


    private void downloadFile(String filename, String savePath) throws MWWebServiceException {
        Client client = ClientBuilder.newClient();
        try {
            Response r = namenode.path(filename).request(MediaType.APPLICATION_JSON).get();
            if (r.getStatus() != 200) {
                throw new MWWebServiceException(r.getStatus()
                        + ": " + r.readEntity(String.class));
            }
            MWFileMetaData md = r.readEntity(MWFileMetaData.class);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (MWFileBlock block : md.blocks()) {
                String blockId = block.id();
                List<MWNodeMetaData> nodes = block.nodes();
                if (nodes == null || nodes.isEmpty()) {
                    throw new MWWebServiceException("No replicas available for block " + blockId);
                }
                byte[] blockContent = null;
                Exception lastError = null;
                for (MWNodeMetaData node : nodes) {
                    WebTarget datanode = client
                            .target("http://" + node.host() + ":" + node.port())
                            .path("datablock")
                            .path(blockId);

                    try {
                        byte[] content = downloadBlock(datanode);
                        if (content != null && content.length > 0) {
                            blockContent = content;
                            break; // success, stop trying other replicas
                        } else {
                            System.err.println("Received empty content for block "
                                    + blockId + " from " + node.host() + ":" + node.port());
                        }
                    } catch (Exception e) {
                        lastError = e;
                        System.err.println("Failed to download block "
                                + blockId + " from " + node.host() + ":" + node.port()
                                + ": " + e.getMessage());
                        // continue with next node
                    }
                }if (blockContent == null) {
                    if (lastError != null) {
                        throw new MWWebServiceException(
                                "Failed to download block " + blockId + " from all replicas", lastError);
                    } else {
                        throw new MWWebServiceException(
                                "Failed to download block " + blockId + " from all replicas (no valid content)");
                    }
                }
                    baos.write(blockContent);
            }
            byte[] data = baos.toByteArray();
            Path outputPath = Paths.get(savePath);
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }

            Files.write(outputPath, data);
        } catch (IOException e) {
            throw new MWWebServiceException(e);
        }
    }

    private void removeFile(String path) throws MWWebServiceException {
        try (Response response = namenode.path(path).request().delete();) {
            if (response.getStatus() != 200) {
                throw new MWWebServiceException(response.getStatus() + ": " + response.readEntity(String.class));
            }
        }
    }

    public void uploadDummyBlockToDataNode(MWNodeMetaData node,
                                           String fileName,
                                           int size) throws MWWebServiceException {



        // create dummy content
        byte[] dummy = new byte[size];
        new Random().nextBytes(dummy);
        File outputFile = new File(fileName);
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(dummy);
        } catch (FileNotFoundException e) {
            System.err.println("Did not find file" + fileName);
        } catch (IOException e) {
            throw new MWWebServiceException(e);
        }

        uploadFile(fileName, 2);
    }

    // #########
    // # SHELL #
    // #########

    public void shell() {
        // Create input reader and process commands
        BufferedReader commandLine = new BufferedReader(new InputStreamReader(System.in));
        while(true) {
            // Print prompt
            System.out.print("[DFS]> ");
            System.out.flush();

            // Read next line
            String command;
            try {
                command = commandLine.readLine();
            } catch(IOException ioe) {
                break;
            }
            if(command == null) break;
            if(command.isEmpty()) continue;

            // Prepare command
            String[] args = splitUnescapeArgs(command);
            if(args.length == 0) continue;
            args[0] = args[0].toLowerCase();

            try {
                // Process command
                boolean loop = processCommand(args);
                if(!loop) break;
            } catch(IllegalArgumentException iae) {
                System.err.println(iae.getMessage());
            } catch(MWWebServiceException wse) {
                System.err.println("Web service error: " + wse.getMessage());
            }
        }

        // Close input reader
        try {
            commandLine.close();
        } catch(IOException ioe) {
            // Ignore
        }
    }

    // Internal function for argument parsing. Do not use.
    private static String[] splitUnescapeArgs(String x) {
        boolean quoted = false, escaping = false;
        LinkedList<String> result = new LinkedList<>();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < x.length(); i++) {
            Character c = x.charAt(i);

            if (escaping) {
                escaping = false;
                sb.append(c);
                continue;
            }

            if (c == '\\') { escaping = true; continue; }
            if (c == '"')  { quoted = ! quoted; continue; }
            if (Character.isWhitespace(c) && ! quoted) {
                if (sb.length() == 0) continue;

                result.add(sb.toString());
                sb = new StringBuilder();
                continue;
            }
            sb.append(c);
        }
        if (sb.length() > 0) result.add(sb.toString());
        return result.toArray(new String[0]);
    }

    private boolean processCommand(String[] args) throws MWWebServiceException {
        switch(args[0]) {
            case "help":
            case "h":
                System.out.println("The following commands are available:\n"
                        + "  help                     Print this text\n"
                        + "  list                     List current directory content\n"
                        + "  upload <file> [replicas] Uploads file to current directory\n"
                        + "  download <file>          Download given file\n"
                        + "  remove <file>            Delete given file\n"
                        + "  quit                     Exit this program"
                );
                break;
            case "list":
            case "ls":
                if (args.length > 1) throw new IllegalArgumentException("Usage: list");
                listFiles();
                break;
            case "upload":
            case "up":
            case "u":
                if(args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: upload <file> [replicas]");

                int replicas = 1;
                if (args.length == 3) replicas = tryParseInt(args[2], -1);
                if (replicas < 1 || replicas > 100) {
                    throw new IllegalArgumentException("Bad number of replicas specified.");
                }

                uploadFile(args[1], replicas);
                break;
            case "download":
            case "down":
            case "d":
                if (args.length != 3) throw new IllegalArgumentException("Usage: download <filename> <savePath>");
                downloadFile(args[1], args[2]);
                break;
            case "remove":
            case "rm":
                if (args.length != 2) throw new IllegalArgumentException("Usage: remove <file>");
                removeFile(args[1]);
                break;
            case "exit":
            case "quit":
            case "x":
            case "q":
                return false;
            case "create-debug":
            case "cd":
                uploadDummyBlockToDataNode(new MWNodeMetaData("127.0.0.1", 8080), "uilfssd", 8*1024*1024);
                System.out.println("upload successful");
                break;
            default:
                throw new IllegalArgumentException("Unknown command: " + args[0] + "\nUse \"help\" to list available commands");
        }
        return true;
    }

    @SuppressWarnings("SameParameterValue")
    private static int tryParseInt(String str, int fallback) {
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException ignore) {}
        return fallback;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: MWDFSClient <namenode>");
            System.exit(1);
        }

        new MWDFSClient(args[0]).shell();
    }
}
