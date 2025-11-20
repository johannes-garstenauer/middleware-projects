package mw.namenode;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.Response.Status;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

@Singleton
@Path("namenode")
public class MWNameNode {
    private Map<String, MWFileMetaData> files;

    public MWNameNode() {
        this.files = new HashMap<>();
    }

    private void addTestFiles() {
        // just for debugging...
        MWFileMetaData[] filesToAdd = new MWFileMetaData[] {
            new MWFileMetaData("empty", 0, Arrays.asList(new MWNodeMetaData[] {})),
            new MWFileMetaData("oneBlock", 100, Arrays.asList(new MWNodeMetaData[] {
                new MWNodeMetaData("127.0.0.1", 8080)
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
        var filesWithoutBlocks = files
            .values()
            .stream()
            .map(file ->
                // set blocks to "null" here, so we don't send that property at all
                new MWFileMetaData(file.name(), file.size(), null))
            .toList();

        return Response.ok(filesWithoutBlocks).build();
    }

    @GET
    @Path("{file}")
    public Response getFile(@PathParam("file") String file) {
        if (!files.containsKey(file)) {
            return Response.status(Status.NOT_FOUND).build();
        }

        return Response.ok(files.get(file)).build();
    }

    public static void main(String[] args) {
        String SERVICE_TARGET = "http://0.0.0.0:60998";

        MWNameNode service = new MWNameNode();
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