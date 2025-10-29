package mw.client;

import mw.path.MWPathServer;
import mw.client.MWRegistryClient;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;


public class MWWebServiceClient extends MWShell {
    WebTarget facebookClient;
    WebTarget pathClient;
    MWRegistryClient registryClient;

    public MWWebServiceClient(String group, String service, String key) {
        // initializes the registry Client, the path Server, path Client and facebook client
        String registryUrl = MWRegistryClient.readRegistryURL();
        registryClient = new MWRegistryClient(registryUrl);
        registryClient.loginViaCLI();


        try {
            String pathUri = registryClient.getValue("gruppe1", "path", "address");
            URI pathServerUri = UriBuilder.fromUri(pathUri).build();
            pathClient = ClientBuilder.newClient().target(pathServerUri);
        } catch (MWWebServiceException e) {
            System.err.println("Error: path client could not be built!");
            System.err.println(e.getMessage());
            System.exit(-1);
        }

        try {
            String registryUri = registryClient.getValue(group, service, key);
            URI uri = UriBuilder.fromUri(registryUri).build();
            facebookClient = ClientBuilder.newClient().target(uri);
        } catch (MWWebServiceException e) {
            System.err.println("Error: facebook client could not be built!");
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    public String[] search(String string) throws MWWebServiceException {
        Response response = facebookClient.path("search")
                .queryParam("string", string)
                .request()
                .get();
        if (response.getStatus() != 200) {
            String body = response.readEntity(String.class);
            throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
        }
        String[] ids = response.readEntity(String[].class);
        response.close();
        return ids;
    }

    public String getName(String id) throws MWWebServiceException {
        Response response = facebookClient.path("names/" + id).request().get();
        if (response.getStatus() != 200) {
            String body = response.readEntity(String.class);
            throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
        }
        String name = response.readEntity(String.class);
        response.close();
        return name;
    }

    public String[] getFriends(String id) throws MWWebServiceException {
        Response response = facebookClient.path("friends/" + id).request().get();
        if (response.getStatus() != 200) {
            String body = response.readEntity(String.class);
            throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
        }
        String[] friends = response.readEntity(String[].class);
        response.close();
        return friends;
    }

    public String[] path(String startId, String endId) throws MWWebServiceException {
        Response response = pathClient.path("path").queryParam("startID", startId)
                .queryParam("endID", endId).request().get();
        if (response.getStatus() != 200) {
            String body = response.readEntity(String.class);
            throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
        }
        return response.readEntity(String[].class);
    }

    protected boolean processCommand(String[] args) throws MWWebServiceException {
        switch (args[0]) {
            case "help":
            case "h":
                System.out.println("The following commands are available: \n"
                        + " help\n"
                        + " search <string>\n"
                        + " friends <id>\n"
                );
                break;
            case "search":
                if (args.length < 2)
                    throw new IllegalArgumentException("Usage: search command: Missing argument");
                String[] ids = search(args[1]);
                for (String id : ids) {
                    String name = getName(id);
                    System.out.println(name + ": " + id);
                }
                break;
            case "friends":
                if (args.length < 2)
                    throw new IllegalArgumentException("Usage: friends command: Missing argument");
                String[] friend_ids = getFriends(args[1]);
                for (String id : friend_ids) {
                    String name = getName(id);
                    System.out.println(name + ": " + id);
                }
                break;
            case "path":
                if (args.length < 3)
                    throw new IllegalArgumentException("Usage: path command: Missing argument");
                String[] path =  path(args[1], args[2]);
                for (String id : path) {
                    String name = getName(id);
                    System.out.println(name + ": " + id);
                }
        }
        return true;
    }

    static void main(String[] args) {
        MWWebServiceClient facebook_client = new MWWebServiceClient("i4", "facebook", "address");
        facebook_client.shell();
    }

}


