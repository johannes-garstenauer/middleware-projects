package mw.client;

import mw.path.MWPathServer;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;


public class MWWebServiceClient extends MWShell {
    WebTarget facebook_client;
    WebTarget pathClient;

    public MWWebServiceClient(String group, String service, String key) {
        String registryUrl = MWRegistryClient.readRegistryURL();
        MWRegistryClient registryClient = new MWRegistryClient(registryUrl);
        registryClient.loginViaCLI();
        MWPathServer pathServer = new MWPathServer();
        pathServer.register(registryClient);
        String pathUri= null;
        try {
            pathUri = registryClient.getValue("gruppe1", "path", "address");
        } catch (MWWebServiceException e) {
            System.err.println("Error: path service uri not received!");
            System.err.println(e.getMessage());
            System.exit(1);
        }
        URI pathServerUri = UriBuilder.fromUri(pathUri).build();
        pathClient = ClientBuilder.newClient().target(pathServerUri);

        String registryUri = null;
        try {
            registryUri = registryClient.getValue(group, service, key);
        } catch (MWWebServiceException e) {
            System.err.println("Error: url could not be acquired!");
            System.err.println(e.getMessage());
            System.exit(1);
        }
        URI uri = UriBuilder.fromUri(registryUri).build();
        facebook_client = ClientBuilder.newClient().target(uri);
    }

    public String[] search(String string) throws MWWebServiceException {
        Response response = facebook_client.path("search")
                .queryParam("string", string)
                .request()
                .get();
        if (response.getStatus() != 200) {
            String body = response.readEntity(String.class);
            throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
        }
        return response.readEntity(String[].class);
    }

    public String getName(String id) throws MWWebServiceException {
        Response response = facebook_client.path("names/" + id).request().get();
        if (response.getStatus() != 200) {
            String body = response.readEntity(String.class);
            throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
        }
        return response.readEntity(String.class);
    }

    public String[] getFriends(String id) throws MWWebServiceException {
        Response response = facebook_client.path("friends/" + id).request().get();
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
                    throw new MWWebServiceException("Usage: search command: Missing argument");
                String[] ids = search(args[1]);
                for (String id : ids) {
                    String name = getName(id);
                    System.out.println(name + ": " + id);
                }
                break;
            case "friends":
                if (args.length < 2)
                    throw new MWWebServiceException("Usage: friends command: Missing argument");
                String[] friend_ids = getFriends(args[1]);
                for (String id : friend_ids) {
                    String name = getName(id);
                    System.out.println(name + ": " + id);
                }
                break;
        }
        return true;
    }

    static void main(String[] args) {
        MWWebServiceClient facebook_client = new MWWebServiceClient("i4", "facebook", "address");
        facebook_client.shell();
    }

}


