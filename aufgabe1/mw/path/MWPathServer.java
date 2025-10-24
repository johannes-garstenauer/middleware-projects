package mw.path;

import mw.client.MWRegistryClient;
import mw.client.MWWebServiceException;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.*;

//TODO: String int conversion are really silly here

@Singleton
@Path("path")
public class MWPathServer {
    MWRegistryClient registryClient;
    WebTarget facebookClient;
    public MWPathServer(MWRegistryClient registryClient,  WebTarget facebookClient) {
        this.registryClient = registryClient;
        this.facebookClient = facebookClient;
    }

    public void register() {
        //this assumes the client is already logged in
        try {
            registryClient.createService("gruppe1", "path");
            registryClient.putValue("gruppe1", "path", "address", "http://[::]/");
        } catch (MWWebServiceException e) {
            System.err.println("Error: could not create service!");
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    @GET
    @Path("{startID}/{endID}")
    public Response get(@PathParam("startID") int startID, @PathParam("endID") int endID) {

        MWPath response = new MWPath();
        String[] test = new String[1];
        test[0] = "test";
        response.path = test;
        return Response.ok(response).build();
        //Map<String, Collection<String>> friendships = getReducedFriendships(startID, endID, response);

        //response.path = MWDijkstra.getShortestPath(
        //        Integer.toString(startID),
        //        Integer.toString(endID),
        //        friendships
        //);

        //response.numberOfIDs = countFriendships(friendships);
        //return Response.ok(response).build();
    }

    private static int countFriendships(Map<String, Collection<String>> friendships) {
        return (int) friendships.values().stream()
                .flatMap(Collection::stream)   // flatten all collections into a stream
                .distinct()                    // remove duplicates
                .count();                      // count unique elements
    }

    private List<String> getFriends(int userID) {
        Response response = facebookClient.path("friends/" + userID).request().get();
        List<String> friends = response.readEntity(new GenericType<>() {});
        response.close();
        return friends;
    }

    /***
     * Returns True if the friendshipId lists have a common element.
     * Linear time complexity w.r.t. set sizes
     *
     * @param setA First set to be analyzed for a common element.
     * @param setB Second set to be analyzed for a common element.
     * @return Boolean value indicating whether or not a match exists between sets.
     */
    private static boolean containAnyMatch(Set<String> setA, Set<String> setB) {
        return setB.stream().anyMatch(setA::contains);
    }

    /***
     * Collect the friendship lists for each user that could be on the path between the given users.
     * Iteratively walks the user friendship graph from both the end and starting points until they meet somewhere.
     * At this point the algorithm terminates.
     *
     * @param startID The user id for the path's beginning.
     * @param endID The user id for the path's end.
     * @return Map containing all friends for each user that may possibly be on the path between given users.
     */
    // TODO update for path param
    // TODO update for counting of calls
    private Map<String, Collection<String>> getReducedFriendships(int startID, int endID, MWPath path) {
        Map<String, Collection<String>> result = new HashMap<>(Collections.emptyMap());

        // Init set of starting and ending points in friendship graph.
        Set<String> startFriendships = new HashSet<>(Collections.emptySet());
        startFriendships.add(String.valueOf(startID));

        Set<String> endFriendships = new HashSet<>(Collections.emptyList());
        endFriendships.add(String.valueOf(endID));

        do {

            //StartFreundeskreis := alle Nutzer , die von startID in i Schritten erreichbar sind;
            for (String startFriend : startFriendships) {
                List<String> tmp_friendships = getFriends(Integer.parseInt(startFriend));
                path.numberOfIDs++;

                // Extend startFriendship set and construct the results map.
                result.put(startFriend, tmp_friendships);
                startFriendships.addAll(tmp_friendships);
            }

            //EndFreundeskreis := alle Nutzer , die von endID in i Schritten erreichbar sind;
            for (String endFriend : endFriendships) {
                List<String> tmp_friendships = getFriends(Integer.parseInt(endFriend));
                path.numberOfIDs++;

                // Extend endFriendship set and construct the results map.
                result.put(endFriend, tmp_friendships);
                endFriendships.addAll(tmp_friendships);
            }
        } while (!containAnyMatch(startFriendships, endFriendships));

        return result;
    }
    public static void main(String[] args) {
        URI uri = UriBuilder.fromUri("http://[::]/").port(12345).build();
        ResourceConfig config = new ResourceConfig(MWPathServer.class);
        GrizzlyHttpServerFactory.createHttpServer(uri, config);
    }
}
