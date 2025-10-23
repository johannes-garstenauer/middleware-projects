package mw.client;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.rmi.registry.Registry;


public class MWWebServiceClient extends MWShell {
    WebTarget client;

    public MWWebServiceClient() {
        String registryUrl = MWRegistryClient.readRegistryURL();
        MWRegistryClient registryClient = new MWRegistryClient(registryUrl);
        //String[] groups = registry_client.listGroups();
        try {
            String[] services = registryClient.listServices("gruppe1");
        } catch (MWWebServiceException e) {
            e.printStackTrace();
            System.exit(1);
        }
        registryClient.loginViaCLI();
        String url = null;
        try {
            url = registryClient.listKeys("i4", "facebook")[0];
        } catch (MWWebServiceException e) {
            System.err.println("Error: url could not be acquired!");
            System.err.println(e.getMessage());
            System.exit(1);
        }
        URI uri = UriBuilder.fromUri(url).build();
        client = ClientBuilder.newClient().target(uri);
    }

    public String[] search(String string) throws MWWebServiceException {
        return null;
    }

    public String getname(String id) throws MWWebServiceException {
        return null;
    }

    public String[] getFriends(String id) throws MWWebServiceException {
        return null;
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
                String[] names = search(args[1]);
                for (String name : names) {
                    System.out.println(name);
                }
                break;
            case "friends":
                if (args.length < 2)
                    throw new MWWebServiceException("Usage: friends command: Missing argument");
                String[] friend_names = getFriends(args[1]);
                for (String name : friend_names) {
                    System.out.println(name);
                }
                break;
        }
        return true;
    }

    public static void main(String[] args) {
        return;
    }

}


