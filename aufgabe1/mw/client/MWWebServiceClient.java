package mw.client;

import mw.path.MWPath;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.function.BiConsumer;


public class MWWebServiceClient extends MWShell {
    WebTarget facebookClient;
    WebTarget pathClient;
    MWRegistryClient registryClient;

    public MWWebServiceClient(String group, String service, String key) {
        // initializes the registry Client, the path Server, path Client and facebook client
        String registryUrl = MWRegistryClient.readRegistryURL();
        registryClient = new MWRegistryClient(registryUrl);
        registryClient.autoLogin();


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
            String facebookUriString = registryClient.getValue(group, service, key);
            URI facebookUri = UriBuilder.fromUri(facebookUriString).build();
            facebookClient = ClientBuilder.newClient().target(facebookUri);
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
        try (Response response = facebookClient.path("names/" + id).request().get()) {
            if (response.getStatus() != 200) {
                String body = response.readEntity(String.class);
                throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
            }
            return response.readEntity(String.class);
        }
    }
    public String[] getNames(String[] ids) throws MWWebServiceException {
        try (Response response = facebookClient.path("names").request().post(Entity.json(ids))) {
            if (response.getStatus() != 200) {
                String body = response.readEntity(String.class);
                throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
            }
            GenericType<String[]> type = new GenericType<>() {};
            return response.readEntity(type);
        }
    }

    public String[] getFriends(String id) throws MWWebServiceException {
        try (Response response = facebookClient.path("friends/" + id).request().get()) {
            if (response.getStatus() != 200) {
                String body = response.readEntity(String.class);
                throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
            }
            return response.readEntity(String[].class);
        }
    }

    public Map<String, HashSet<String>> getFriends (String[] ids)
            throws MWWebServiceException {
        try (Response response = facebookClient.path("friends").request().post(Entity.json(ids))) {
            if (response.getStatus() != 200) {
                String body = response.readEntity(String.class);
                throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
            }
            GenericType<Map<String, HashSet<String>>> type = new GenericType<>() {};
            return response.readEntity(type);
        }
    }


    public MWPath path(String startId, String endId, boolean batching) throws MWWebServiceException {
        try (Response response = pathClient.queryParam("startID", startId)
                .queryParam("endID", endId).queryParam("batching", batching)
                .request().get()) {
            if (response.getStatus() != 200) {
                String body = response.readEntity(String.class);
                throw new MWWebServiceException("HTTP " + response.getStatus() + ": " + body);
            }
            GenericType<MWPath> type = new GenericType<>() {};
            return response.readEntity(type);
        }
    }

    protected boolean processCommand(String[] args) throws MWWebServiceException {
        topSwitch: switch (args[0]) {
            case "help", "h" -> {
                System.out.println("The following commands are available: \n"
                    + " help\n"
                    + " search <string>\n"
                    + " friends <id>\n"
                    + " friends-batched <id> ...\n"
                    + " get-names-batched <id> ...\n"
                    + " path <id> <id>\n"
                );
            }
            case "search" -> {
                if (args.length != 2)
                    throw new IllegalArgumentException("Usage: search command: Missing argument");
                String[] ids = search(args[1]);
                for (String id : ids) {
                    String name = getName(id);
                    System.out.println(name + ": " + id);
                }
            }
            case "get-names-batched" -> {
                if (args.length < 2)
                    throw new IllegalArgumentException("Usage: get-names-batched command: Missing argument");
                String[] ids = Arrays.stream(args).skip(1).toArray(String[]::new);
                String[] resNames = getNames(ids);
                for (int i = 0; i < ids.length; i++) {
                    System.out.println(ids[i] + ": " + resNames[i]);
                }
            }
            case "friends" -> {
                if (args.length != 2)
                    throw new IllegalArgumentException("Usage: friends command: Missing argument");
                String[] friend_ids = getFriends(args[1]);
                for (String id : friend_ids) {
                    String name = getName(id);
                    System.out.println(name + ": " + id);
                }
            }
            case "friends-batched" -> {
                if (args.length < 2)
                    throw new IllegalArgumentException("Usage: friends command: Missing argument");
                String[] ids = Arrays.stream(args).skip(1).toArray(String[]::new);
                Map<String, HashSet<String>> res = getFriends(ids);
                for (String id : ids) {
                    String name = getName(id);
                    System.out.println("### " + name + " (" + id + ") " + "###");

                    HashSet<String> set = res.get(id);
                    for (String friendId : set) {
                        String friendName = getName(friendId);
                        System.out.println(friendName + ": " + friendId);
                    }
                    System.out.println();
                }

            }
            case "path" -> {
                if (args.length < 3)
                    throw new IllegalArgumentException("Usage: path command: Missing argument");
                boolean batching = false;
                if (args.length >= 4) {
                    switch (args[3].toLowerCase()) {
                        case "yes":
                        case "true":
                        case "1":
                            batching = true;
                            break;
                        case "no":
                        case "false":
                        case "0":
                            break;
                        default:
                            System.err.println("Invalid boolean value: " + args[3]);
                            break topSwitch;
                    }

                    batching = Boolean.parseBoolean(args[3]);
                }

                MWPath path = path(args[1], args[2], batching);
                // publish stats into registry
                BiConsumer<String, Integer> updateValue = (property, value) -> {
                    try {
                        int prev_value;
                        if (Arrays.asList(registryClient.listKeys("gruppe1", "path")).contains(property)) {
                            String val = registryClient.getValue("gruppe1", "path", property);
                            prev_value = Integer.parseInt(val);
                        } else {
                            prev_value = 0;
                        }

                        int newValue = prev_value + value;
                        registryClient.putValue("gruppe1", "path", property, Integer.toString(newValue));
                    } catch (MWWebServiceException | NumberFormatException e) {
                        System.err.println("Could not update value " + property + "!");
                        e.printStackTrace();
                    }
                };
                updateValue.accept("number-of-ids", path.numberOfIDs);
                updateValue.accept("number-of-calls", path.numberOfCalls);

                // print result
                for (String id : path.path) {
                    String name = getName(id);
                    System.out.println(name + ": " + id);
                }
                System.out.println("Number of Calls: " + path.numberOfCalls);
                System.out.println("Number of IDs: " + path.numberOfIDs);
            }
        }
        return true;
    }

    public static void main(String[] args) {
        MWWebServiceClient facebook_client = new MWWebServiceClient("i4", "facebook", "address");
        facebook_client.shell();
    }

}


