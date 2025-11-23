package mw.dfsclient;

import mw.datanode.MWDataNode;
import mw.datanode.MWDataNodeWebService;
import mw.namenode.MWFileBlock;
import mw.namenode.MWFileMetaData;
import mw.namenode.MWNodeMetaData;
import org.glassfish.grizzly.utils.ArrayUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.function.IntPredicate;
import java.util.List;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.client.Entity;




public class MWDFSClient {
    // JAX-RS HTTP Client to reach namenode specified at command line
    private final WebTarget namenode;
    private static final int BLOCKSIZE = 1024*1024;

    public MWDFSClient(String namenode_str) {
        Client client = ClientBuilder.newClient();
        namenode = client.target("http://" + namenode_str + "/namenode");
    }


    // ##############################
    // # LOW-LEVEL PROTOCOL HELPERS #
    // ##############################

    private void uploadBlock(byte[] block, WebTarget datanode) throws MWWebServiceException {
        validateBlockSize(block);
        try (Response uploadResponse = datanode.request()
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

    private static void validateBlockId(String blockId) throws MWWebServiceException {
        IntPredicate isValidChar = charcode -> Character.isLetterOrDigit(charcode) || charcode == '-';
        if (blockId.isEmpty() || !blockId.chars().allMatch(isValidChar)) {
            throw new MWWebServiceException("Validation for blockId " + blockId + "failed!");
        }
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
        Response response = namenode.path("/datablock/").request().get();
        if (response.getStatus() != 200) {
            throw new MWWebServiceException(response.getStatus() + ": " + response.readEntity(String.class));
        }
        String[] files = response.readEntity(String[].class);
        for  (String file : files) {
            System.out.println(file);
        }
    }

    private void uploadFile(String sPath, int replicas) throws MWWebServiceException {
        Path path = Paths.get(sPath);
        // get new lease
        try (Response response = namenode.path(path + "/lock").request().post(Entity.text(""))) {
            if (response.getStatus() != 200) {
                System.err.println("File is already locked!");
                throw new MWWebServiceException(response.getStatus() + ": " + response.readEntity(String.class));
            }
            try {
                byte[] content = Files.readAllBytes(path);
                for (int i = 0; i <= content.length; i += BLOCKSIZE) {
                    byte[] block = Arrays.copyOfRange(content, i, i + BLOCKSIZE);
                    uploadBlock(block, namenode);
                }
            } catch (IOException e) {
                System.err.println("Failed to read input file!");
                throw new MWWebServiceException("Failed to read input file!", e);
            }
        }
    }

    private void downloadFile(String path) throws MWWebServiceException {
        Client client = ClientBuilder.newClient();
        try {
            Response r = namenode.path(path).request().get();
            if (r.getStatus() != 200) {
                throw new MWWebServiceException(r.getStatus() + ": " + r.readEntity(String.class));
            }
            MWFileMetaData md = r.readEntity(MWFileMetaData.class);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (MWFileBlock block : md.blocks()) {
                WebTarget datanode = client.target(block.node().host() + ":" + block.node().port());
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
        // FIXME: Implement
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
