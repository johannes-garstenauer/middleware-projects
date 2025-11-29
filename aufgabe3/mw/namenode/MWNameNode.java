package mw.namenode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.Response.Status;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

@Singleton
@Path("namenode")
public class MWNameNode {

    private Map<String, MWFileMetaData> files = new HashMap<>();
    private MWFileLeaseContainer fileLeases;
    private List<MWNodeMetaData> dataNodes;
    private Random random = new Random();
    private MWUniqueIdGenerator blockIdGenerator = new MWUniqueIdGenerator();

    private MWNameNodePersistence persistence;
    private int snapshotCounter = 0;
    private final int SNAPSHOT_THRESHOLD = 10; // after how many ops to create a new snapshot

    public MWNameNode(List<MWNodeMetaData> dataNodes, long leaseDurationMs) {
        this.dataNodes = dataNodes;
        this.fileLeases = new MWFileLeaseContainer(leaseDurationMs);
    }

    void persistenceClearFiles() {
        this.files.clear();
    }

    void persistencePutFile(String name, MWFileMetaData meta) {
        synchronized (files) {
            files.put(name, meta);
        }
    }

    void persistenceRemoveFile(String name) {
        synchronized (files) {
            files.remove(name);
        }
    }

    /**
     * Restore a lease during snapshot/WAL replay.
     */
    void persistenceRestoreLease(String name, MWFileLease lease) {
        synchronized (fileLeases) {
            fileLeases.restoreLease(name, lease);
        }
    }

    void persistenceRemoveLease(String name) {
        synchronized (fileLeases) {
            fileLeases.removeLease(name);
        }
    }

    // TODO: when used?
    void setPersistence(MWNameNodePersistence p) {
        this.persistence = p;
    }

    /**
     * Create snapshot bytes: files then leases.
     */
    byte[] persistenceCreateSnapshot() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            // files
            Map<String, MWFileMetaData> filesSnapshot;
            synchronized (files) {
                filesSnapshot = new HashMap<>(files);
            }
            out.writeInt(filesSnapshot.size());
            for (Map.Entry<String, MWFileMetaData> e : filesSnapshot.entrySet()) {
                byte[] nameBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);

                byte[] meta = e.getValue().serialize();
                out.writeInt(meta.length);
                out.write(meta);
            }

            // leases
            Map<String, MWFileLease> leasesSnapshot = fileLeases.getAllLeasesSnapshot();
            out.writeInt(leasesSnapshot.size());
            for (Map.Entry<String, MWFileLease> e : leasesSnapshot.entrySet()) {
                byte[] nameBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);

                byte[] leaseBytes = e.getValue().serialize();
                out.writeInt(leaseBytes.length);
                out.write(leaseBytes);
            }
            out.flush();
            return baos.toByteArray();
        }
    }

    private byte[] buildCreateOrUpdateOp(String name, byte[] meta) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(1); // op code
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
            out.writeInt(meta.length);
            out.write(meta);
            out.flush();
            return baos.toByteArray();
        }
    }

    private byte[] buildDeleteOp(String name) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(2); // op code
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
            out.flush();
            return baos.toByteArray();
        }
    }

    private byte[] buildLeaseOp(boolean put, String name, byte[] leaseBytes) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(3); // op code
            out.writeByte(put ? 1 : 0); // action
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
            if (put) {
                out.writeInt(leaseBytes.length);
                out.write(leaseBytes);
            }
            out.flush();
            return baos.toByteArray();
        }
    }

    private void maybeSnapshot() {
        if (persistence == null) return;
        snapshotCounter++;
        if (snapshotCounter >= SNAPSHOT_THRESHOLD) {
            try {
                byte[] snap = persistenceCreateSnapshot();
                persistence.snapshot(snap);
            } catch (IOException e) {
                // snapshot failure: log to stderr, but do not fail the client
                System.err.println("Failed to create snapshot: " + e.getMessage());
                e.printStackTrace();
            } finally {
                snapshotCounter = 0;
            }
        }
    }

    private void appendOp(byte[] op)  {
        if (persistence != null) {
            try {
                persistence.appendOp(op);
            } catch (IOException e) {
                throw new RuntimeException("WAL append failed: " + e.getMessage(), e);
            }
        }
    }

    private void addTestFiles() {
        // just for debugging...
        MWFileMetaData[] filesToAdd = new MWFileMetaData[] {
            new MWFileMetaData("empty", 0, Arrays.asList(new MWFileBlock[] {})),
            new MWFileMetaData("oneBlock", 100, Arrays.asList(new MWFileBlock[] {
                new MWFileBlock("dawdwa", new MWNodeMetaData("127.0.0.1", 8080))
            }))
        };

        for (MWFileMetaData file : filesToAdd) {
            files.put(file.name(), file);
        }
    }

    @GET
    public Response listFiles() {
        // list all filenames with their corresponding size
        // we therefore create a new list of all files without their corresponding blocks
        List<MWFileMetaData> filesWithoutBlocks;
        synchronized (files) {
            filesWithoutBlocks = files
                .values()
                .stream()
                .map(file ->
                    // set blocks to "null" here, so we don't send that property at all
                    new MWFileMetaData(file.name(), file.size(), null))
                .toList();
        }

        return Response.ok(filesWithoutBlocks).build();
    }

    @GET
    @Path("{file}")
    public Response getFile(@PathParam("file") String file) {
        synchronized (files) {
            if (!files.containsKey(file)) {
                return Response.status(Status.NOT_FOUND).build();
            }

            return Response.ok(files.get(file)).build();
        }
    }

    @POST
    @Path("{file}/alloc")
    public Response allocBlock(@PathParam("file") String file) {
        // alloc does not change metadata
        // therefore we do not even create non-existent files

        MWNodeMetaData dataNode = dataNodes.get(random.nextInt(dataNodes.size()));
        String blockId;
        synchronized (blockIdGenerator) {
            blockId = blockIdGenerator.generateUniqueId(file);
        }

        return Response.ok(new MWFileBlock(blockId, dataNode)).build();
    }

    @POST
    @Path("{file}/lock")
    public Response lockFile(@PathParam("file") String file, @QueryParam("renewLease") String renewLease) {
        try {
            synchronized (files) {
                if (!files.containsKey(file)) {
                    // locking non existent files creates them
                    MWFileMetaData md = new MWFileMetaData(file, 0, null);
                    if (persistence != null) {
                        byte[] op = buildCreateOrUpdateOp(file, md.serialize());
                        appendOp(op);
                    }
                    files.put(file, md);
                }
            }

            String leaseId;
            synchronized (fileLeases) {
                if (renewLease == null) {
                    // case 1: create new lease
                    if (fileLeases.hasActiveLease(file)) {
                        return Response.status(Status.CONFLICT).build();
                    }
                    leaseId = fileLeases.renewLease(file);
                    MWFileLease newLease = fileLeases.getLease(file);

                    if (persistence != null) {
                        byte[] leaseBytes = newLease.serialize();
                        byte[] op = buildLeaseOp(true, file, leaseBytes);
                        appendOp(op);
                    }

                } else {
                    // case 2: renew existing lease
                    if (!fileLeases.hasActiveLease(file)) {
                        // no lease found for this file
                        // instead of creating new lease, its probably better to tell the client
                        // that the lease expired or did not exist in the first place
                        return Response.status(Status.NOT_FOUND).build();
                    }

                    if (!fileLeases.getLease(file).leaseID().equals(renewLease)) {
                        // wrong lease id
                        return Response.status(Status.FORBIDDEN).build();
                    }

                    leaseId = fileLeases.renewLease(file);
                    MWFileLease renewed = fileLeases.getLease(file);
                    if (persistence != null) {
                        byte[] leaseBytes = renewed.serialize();
                        byte[] op = buildLeaseOp(true, file, leaseBytes);
                        appendOp(op);
                    }
                }

                // snapshot counter: this handler may have created file and/or lease -> increment
                synchronized (files) {
                    synchronized (fileLeases) {
                        maybeSnapshot();
                    }
                }
            }
            return Response.ok(leaseId).build();
        } catch (IOException e) {
            e.printStackTrace();
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }


    @POST
    @Path("{file}/unlock")
    public Response unlockFile(@PathParam("file") String file, @QueryParam("leaseId") String leaseId) {
        try {
            synchronized (fileLeases) {
                MWFileLease lease = fileLeases.getLease(file);
                if (lease == null) {
                    // no lease found for this file
                    return Response.status(Status.NOT_FOUND).build();
                }

                if (!lease.leaseID().equals(leaseId)) {
                    // wrong lease id
                    return Response.status(Status.FORBIDDEN).build();
                }

                if (persistence != null) {
                    byte[] op = buildLeaseOp(false, file, null);
                    appendOp(op);
                }

                fileLeases.removeLease(file);

                // snapshot counter
                synchronized (files) {
                    synchronized (fileLeases) {
                        maybeSnapshot();
                    }
                }
            }

            return Response.status(Status.OK).build();
        } catch (IOException e) {
            e.printStackTrace();
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("{file}/commit")
    public Response updateFileMetadata(@PathParam("file") String file, MWFileMetaData newMetadata, @QueryParam("leaseId") String leaseId) {
        try {
            synchronized (fileLeases) {
                MWFileLease lease = fileLeases.getLease(file);
                if (lease == null) {
                    // no lease found for this file
                    return Response.status(Status.NOT_FOUND).build();
                }

                if (!lease.leaseID().equals(leaseId)) {
                    // wrong lease id
                    return Response.status(Status.FORBIDDEN).build();
                }
            }

            synchronized (files) {
                if (persistence != null) {
                    byte[] metaBytes = newMetadata.serialize();
                    byte[] op = buildCreateOrUpdateOp(file, metaBytes);
                    appendOp(op);
                }

                files.put(file, newMetadata);

                synchronized (fileLeases) {
                    maybeSnapshot();
                }
            }

            return Response.status(Status.OK).build();
        }  catch (IOException e) {
            e.printStackTrace();
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("{file}")
    public Response deleteFile(@PathParam("file") String file) {
        try {
            synchronized (files) {
                synchronized (fileLeases) {
                    if (fileLeases.hasActiveLease(file)) {
                        // cannot delete while a lease is active
                        return Response.status(Status.CONFLICT).build();
                    }

                    if (!files.containsKey(file)) {
                        return Response.status(Status.NOT_FOUND).build();
                    }

                    if (persistence != null) {
                        byte[] op = buildDeleteOp(file);
                        appendOp(op);
                    }

                    files.remove(file);

                    maybeSnapshot();
                }
            }

            return Response.status(Status.OK).build();
        } catch (IOException e) {
            e.printStackTrace();
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    public static void main(String[] args) {
        // ###### 1. SETTINGS ######
        // 10 minutes
        final long LEASE_DURATION_MS = 10 * 60 * 1000;

        final String SERVICE_TARGET = "http://0.0.0.0:60998";

        // ###### 2. PARSING ARGUMENTS ######
        // TODO remove
        if (args.length == 0) {
            // for debugging: some default data nodes...
            args = new String[] {"127.0.0.1,8080", "127.0.0.1,1203", "127.0.0.1,3094", "127.0.0.1,3000"};
        }

        List<MWNodeMetaData> dataNodes = Arrays.stream(args)
            .map(arg -> {
                String[] parts = arg.split(",");
                if (parts.length != 2) {
                    System.err.println("usage: MWNameNode [data-node-url1,data-node-port1] ...");
                    System.exit(1);
                }

                return new MWNodeMetaData(parts[0], Integer.parseInt(parts[1]));
            })
            .toList();

        // ###### 3. INITIALIZING & STARTING SERVER ######
        MWNameNode service = new MWNameNode(dataNodes, LEASE_DURATION_MS);

        // persistence initialization
        try {
            java.nio.file.Path stateDir = Paths.get("state");
            MWNameNodePersistence persistence = new MWNameNodePersistence(stateDir);
            service.setPersistence(persistence);
            // load snapshot + WAL
            persistence.load(new PersistenceHandler(service));
        } catch (IOException e) {
            System.err.println("Failed to initialize persistence: " + e.getMessage());
            e.printStackTrace();
            // continue without persistence
        }

        // TODO remove
        service.addTestFiles();

        ResourceConfig rc = new ResourceConfig().register(service);
        rc.register(MWErrorHandler.class);

        URI uri = UriBuilder.fromUri(SERVICE_TARGET).build();
        HttpServer server = GrizzlyHttpServerFactory.createHttpServer(uri, rc);
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Could not start http server at " + uri.toString() + ": " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        System.out.println("Name-Node running at " + uri.toString());
        System.out.println("Type \"stop\" to close it.");

        // give the user a way to stop the server gracefully
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("> ");
            while (!scanner.nextLine().equalsIgnoreCase("stop")) {
                System.out.println("Not a valid option.");
                System.out.print("> ");
            }
        }
        System.out.println("Closing server...");
        // on shutdown create snapshot to truncate WAL
        if (service.persistence != null) {
            try {
                byte[] snap = service.persistenceCreateSnapshot();
                service.persistence.snapshot(snap);
            } catch (IOException e) {
                System.err.println("Failed to write final snapshot: " + e.getMessage());
                e.printStackTrace();
            }
        }
        server.shutdown();
        System.out.println("Bye");
    }
}