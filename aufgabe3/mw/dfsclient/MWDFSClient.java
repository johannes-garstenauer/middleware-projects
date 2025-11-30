package mw.dfsclient;

import mw.namenode.MWFileBlock;
import mw.namenode.MWFileMetaData;
import mw.namenode.MWNodeMetaData;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.client.Entity;


// TODO: debugging script (run datanode upload file)

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


    // ###########################
    // # COMMAND IMPLEMENTATIONS #
    // ###########################

    private void listFiles() throws MWWebServiceException {
        Response response = namenode.request().get();
        if (response.getStatus() != 200) {
            throw new MWWebServiceException(response.getStatus() + ": " + response.readEntity(String.class));
        }
        List<MWFileMetaData> files = response.readEntity(
                new GenericType<List<MWFileMetaData>>() {}
        );
        for  (MWFileMetaData file : files) {
            System.out.println(file.name() + " (" + file.size() + " bytes)");
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
        // get lease
        // TODO: periodically refresh lease
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
            // upload blocks
            ArrayList<MWFileBlock> blocks = new ArrayList<MWFileBlock>();
            for (int offset = 0; offset < content.length; offset += BLOCKSIZE) {
                int end = Math.min(content.length, offset + BLOCKSIZE);
                byte[] block = Arrays.copyOfRange(content, offset, end);
                MWFileBlock fileBlock;
                try (Response allocResponse = namenode
                        .path(filename)
                        .path("alloc")
                        .request()
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
                MWNodeMetaData nodeMetaData = fileBlock.node();
                String blockId = fileBlock.id();
                WebTarget datanode = client
                        .target("http://" + nodeMetaData.host()
                                + ":" + nodeMetaData.port())
                        .path("datablock");
                uploadBlock(block, datanode, blockId);

                // commit metadata
                try (Response commitResponse = namenode.path(filename)
                        .path("commit")
                        .queryParam("leaseId", leaseId)
                        .request()
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


    private void downloadFile(String path) throws MWWebServiceException {
        Client client = ClientBuilder.newClient();
        try {
            Response r = namenode.path(path).request().get();
            if (r.getStatus() != 200) {
                throw new MWWebServiceException(r.getStatus()
                        + ": " + r.readEntity(String.class));
            }
            MWFileMetaData md = r.readEntity(MWFileMetaData.class);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (MWFileBlock block : md.blocks()) {
                String blockId = block.id();
                WebTarget datanode = client.target("http://" + block.node().host()
                        + ":" + block.node().port()).path("datablock").path(blockId);
                byte[] content = downloadBlock(datanode);
                if (content != null && content.length > 0) {
                    baos.write(content);
                }
            }
            byte[] data = baos.toByteArray();
            Path outputPath = Paths.get(path);
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

        uploadFile(fileName, 1);
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
                if (args.length != 2) throw new IllegalArgumentException("Usage: download <file>");
                downloadFile(args[1]);
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
