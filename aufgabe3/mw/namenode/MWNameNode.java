package mw.namenode;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
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

    public MWNameNode(List<MWNodeMetaData> dataNodes, long leaseDurationMs) {
        this.dataNodes = dataNodes;
        this.fileLeases = new MWFileLeaseContainer(leaseDurationMs);
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
        synchronized (files) {
            if (!files.containsKey(file)) {
                // locking non existent files creates them
                files.put(file, new MWFileMetaData(file, 0, null));
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
            }
        }

        return Response.ok(leaseId).build();
    }


    @POST
    @Path("{file}/unlock")
    public Response unlockFile(@PathParam("file") String file, @QueryParam("leaseId") String leaseId) {
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

            fileLeases.removeLease(file);
        }

        return Response.status(Status.OK).build();
    }

    @POST
    @Path("{file}/commit")
    public Response updateFileMetadata(@PathParam("file") String file, MWFileMetaData newMetadata, @QueryParam("leaseId") String leaseId) {
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
            files.put(file, newMetadata);
        }

        return Response.status(Status.OK).build();
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
        server.shutdown();
        System.out.println("Bye");
    }

}