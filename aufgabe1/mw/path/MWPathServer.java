package mw.path;

import mw.client.MWRegistryClient;
import mw.client.MWWebServiceException;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

//TODO: String int conversion are really silly here

@Singleton
@Path("path")
public class MWPathServer implements AutoCloseable {
    private final MWRegistryClient registryClient;
    private final WebTarget facebookClient;
    
    //private static final bool I

    public MWPathServer(MWRegistryClient registryClient, WebTarget facebookClient) {
        this.registryClient = registryClient;
        this.facebookClient = facebookClient;
    }

    public void register() {
        //this assumes the client is already logged in
        try {
            registryClient.createService("gruppe1", "path");
            registryClient.putValue("gruppe1", "path",
                    "address", "http://localhost:12345/");
        } catch (MWWebServiceException e) {
            System.err.println("Error: could not create service!");
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    @GET
    public Response get(@QueryParam("startID") String startID,
                        @QueryParam("endID") String endID,
                        @QueryParam("batching") boolean batching) {
        MWPath response = new MWPath();
        Map<String, Collection<String>> friendships;
        if (batching) {
            friendships = getReducedFriendshipsBatched(startID, endID, response);
        } else {
            friendships = getReducedFriendships(startID, endID, response);
        }

        response.path = MWDijkstra.getShortestPath(startID, endID, friendships);

        response.numberOfIDs = countFriendships(friendships);
        return Response.ok(response).build();
    }

    private static int countFriendships(Map<String, Collection<String>> friendships) {
        return (int) friendships.values().stream()
                .flatMap(Collection::stream)   // flatten all collections into a stream
                .distinct()                    // remove duplicates
                .count();                      // count unique elements
    }

    private List<String> getFriends(String userID) throws MWWebServiceException{
        Response response = facebookClient.path("friends/" + userID).request().get();
        if (response.getStatus() != 200) {
            String body = response.readEntity(String.class);
            throw new MWWebServiceException("HTTP" + response.getStatus() + ": " + body);
        }
        List<String> friends = response.readEntity(new GenericType<>() {});
        response.close();
        return friends;
    }

    private Map<String, HashSet<String>> getFriends(String[] ids)
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

    /***
     * Returns True if the friendshipId lists have a common element.
     * Linear time complexity w.r.t. set sizes
     *
     * @param setA First set to be analyzed for a common element.
     * @param setB Second set to be analyzed for a common element.
     * @return Boolean value indicating whether a match exists between sets.
     */
    private static boolean containAnyMatch(Set<String> setA, Set<String> setB) {
        return setB.stream().anyMatch(setA::contains);
    }

    /***
     * Collect the friendship lists for each user that could be on the path between the
     * given users.
     * Iteratively walks the user friendship graph from both the end and starting points
     * until they meet somewhere.
     * At this point the algorithm terminates.
     *
     * @param startID The user id for the path's beginning.
     * @param endID The user id for the path's end.
     * @return Map containing all friends for each user that may be on the path
     * between given users.
     */
    // TODO update for path param
    // TODO update for counting of calls
    // TODO time measurements
    private Map<String, Collection<String>> getReducedFriendships(String startID,
                                                                  String endID,
                                                                  MWPath path) {
        Map<String, Collection<String>> result = new HashMap<>(Collections.emptyMap());

        // Init set of starting and ending points in friendship graph.
        Set<String> startFriendships = new HashSet<>(Collections.emptySet());
        startFriendships.add(String.valueOf(startID));

        Set<String> endFriendships = new HashSet<>(Collections.emptyList());
        endFriendships.add(String.valueOf(endID));

        do {

            //StartFreundeskreis := alle Nutzer , die von startID in i Schritten
            // erreichbar sind;
            List<String> newFriends = new ArrayList<>();
            for (String startFriend : startFriendships) {
                List<String> tmp_friendships = null;
                try {
                    tmp_friendships = getFriends(startFriend);
                    path.numberOfCalls++;
                } catch (MWWebServiceException e) {
                    System.err.println("Error: could not get friends of user " + startFriend);
                    System.err.println(e.getMessage());
                    System.exit(-1);
                }

                // Extend startFriendship set and construct the results map.
                result.put(startFriend, tmp_friendships);
                newFriends.addAll(tmp_friendships);
            }
            startFriendships.addAll(newFriends);

            //EndFreundeskreis := alle Nutzer , die von endID in
            // i Schritten erreichbar sind;
            newFriends = new ArrayList<>();
            for (String endFriend : endFriendships) {
                List<String> tmp_friendships = null;
                try {
                    tmp_friendships = getFriends(endFriend);
                    path.numberOfCalls++;
                } catch (MWWebServiceException e) {
                    System.err.println("Error: could not get friends of user " + endFriend);
                    System.err.println(e.getMessage());
                    System.exit(-1);
                }

                // Extend endFriendship set and construct the results map.
                result.put(endFriend, tmp_friendships);
                newFriends.addAll(tmp_friendships);
            }
            endFriendships.addAll(newFriends);

        } while (!containAnyMatch(startFriendships, endFriendships));

        return result;
    }

    private Map<String, Collection<String>> getReducedFriendshipsBatched(String startID,
                                                                  String endID,
                                                                  MWPath path) {
        Map<String, Collection<String>> result = new HashMap<>(Collections.emptyMap());

        // Init set of starting and ending points in friendship graph.
        Set<String> startFriendships = new HashSet<>(Collections.emptySet());
        startFriendships.add(String.valueOf(startID));

        Set<String> endFriendships = new HashSet<>(Collections.emptyList());
        endFriendships.add(String.valueOf(endID));

        do {

            //StartFreundeskreis := alle Nutzer , die von startID in i Schritten
            // erreichbar sind;
            try {
                Map<String, HashSet<String>> newFriends = getFriends(startFriendships.toArray(new String[0]));
                path.numberOfCalls++;

                startFriendships.addAll(newFriends.values().stream().flatMap(Set::stream).collect(Collectors.toSet()));
                result.putAll(newFriends);
            } catch (MWWebServiceException e) {
                System.err.println("Error: could not get friends of user " + Arrays.toString(startFriendships.toArray()));
                System.err.println(e.getMessage());
                System.exit(-1);
            }

            //EndFreundeskreis := alle Nutzer , die von endID in
            // i Schritten erreichbar sind;
            try {
                Map<String, HashSet<String>> newFriends = getFriends(endFriendships.toArray(new String[0]));
                path.numberOfCalls++;

                endFriendships.addAll(newFriends.values().stream().flatMap(Set::stream).collect(Collectors.toSet()));
                result.putAll(newFriends);
            } catch (MWWebServiceException e) {
                System.err.println("Error: could not get friends of user " + Arrays.toString(startFriendships.toArray()));
                System.err.println(e.getMessage());
                System.exit(-1);
            }
        } while (!containAnyMatch(startFriendships, endFriendships));

        return result;
    }

    /**
     * Clean up server by removing itself from the registry.
     */
    public void close() throws MWWebServiceException {
        registryClient.deleteValue("gruppe1", "path", "address");
    }

    public static void main(String[] args) {
        String registryUrl = MWRegistryClient.readRegistryURL();
        MWRegistryClient reg = new MWRegistryClient(registryUrl);
        reg.autoLogin();

        String facebookUriString;
        try {
            facebookUriString = reg.getValue("i4",
                    "facebook", "address");
        } catch (MWWebServiceException e) {
            System.err.println("Error: Could not retrieve facebook url!");
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        URI facebookUri = UriBuilder.fromUri(facebookUriString).build();
        WebTarget facebookClient = ClientBuilder.newClient()
                .register(mw.util.ClientTimingFilter.class).target(facebookUri);

        MWPathServer pathServer = new MWPathServer(reg, facebookClient);
        try {
            pathServer.register();
            URI uri = UriBuilder.fromUri("http://0.0.0.0/")
                    .port(12345).build();
            ResourceConfig rc = new ResourceConfig().register(pathServer)
                    .register(mw.util.RequestTimingFilter.class);
            HttpServer server = GrizzlyHttpServerFactory.createHttpServer(uri, rc);
            server.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
