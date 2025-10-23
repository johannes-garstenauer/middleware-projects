package mw.path;

import mw.client.MWRegistryClient;
import mw.client.MWWebServiceException;

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

    @GET
    @Path("{startID}/{endID}")
    public Response get(@PathParam("startID") int startID, @PathParam("endID") int endID) {

        Map<String, Collection<String>> friendships = getReducedFriendships(startID, endID);

        MWDijkstra.getShortestPath(
                Integer.toString(startID),
                Integer.toString(endID),
                friendships
        );

        return Response.ok(new MWPath()).build();
    }

    private static List<String> getFriends(int userID) {

        MWRegistryClient mwRegistryClient = initRegistryClient("username", "pw");
        WebTarget client = getServiceClient(mwRegistryClient, "i4", "facebook", "address");

        Response response = client.path("friends/" + userID).request().get();
        List<String> friends = response.readEntity(new GenericType<List<String>>() {
        });
        response.close();

        return friends;
    }

    // TODO: static global method for registry creation (now code is just copied from Moritz)
    // TODO where to put login credentials?
    private static MWRegistryClient initRegistryClient(String username, String password) {
        String registryUrl = MWRegistryClient.readRegistryURL();
        MWRegistryClient registryClient = new MWRegistryClient(registryUrl);

        // TODO alternatively loginviaCLI
        registryClient.login(username, password);
        return registryClient;
    }

    // TODO also global as its code duplicate from Moritz
    private static WebTarget getServiceClient(MWRegistryClient registryClient, String group, String service, String key) {
        String url = null;

        try {
            url = registryClient.getValue(group, service, key);
        } catch (MWWebServiceException e) {

            System.err.println("Error: url could not be acquired!");
            System.err.println(e.getMessage());
            System.exit(1);
        }
        URI uri = UriBuilder.fromUri(url).build();
        return ClientBuilder.newClient().target(uri);
    }


    /***
     * Returns True if the friendshipId lists have a common element.
     * Linear time complexity w.r.t. set sizes
     *
     * @param setA First set to be analyzed for a common element.
     * @param setB Second set to be analyzed for a common element.
     * @return Boolean value indicating whether or not a match exists between sets.
     */
    private static boolean areConnected(Set<String> setA, Set<String> setB) {
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
    private static Map<String, Collection<String>> getReducedFriendships(int startID, int endID) {
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

                // Extend startFriendship set and construct the results map.
                result.put(startFriend, tmp_friendships);
                startFriendships.addAll(tmp_friendships);
            }

            //EndFreundeskreis := alle Nutzer , die von endID in i Schritten erreichbar sind;
            for (String endFriend : endFriendships) {
                List<String> tmp_friendships = getFriends(Integer.parseInt(endFriend));

                // Extend endFriendship set and construct the results map.
                result.put(endFriend, tmp_friendships);
                endFriendships.addAll(tmp_friendships);
            }
        } while (!areConnected(startFriendships, endFriendships));

        return result;
    }
}
