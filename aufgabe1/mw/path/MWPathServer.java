package mw.path;

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

        Map<String, Collection<String>> friendships = reduceFriendships(startID, endID);

        MWDijkstra.getShortestPath(
                Integer.toString(startID),
                Integer.toString(endID),
                friendships
        );

        return Response.ok(new MWPath()).build();
    }

    // TODO called each tome -> what abt in batch?
    private static List<String> getFriends(int userID) {

        //TODO: fetch from registry
        URI uri = UriBuilder.fromUri("http://localhost/").port(12345).build();
        WebTarget client = ClientBuilder.newClient().target(uri).path("facebook");

        Response response = client.path("friends/" + userID).request().get();
        List<String> friends = response.readEntity(new GenericType<List<String>>() {});
        response.close();

        return friends;
    }

    /***
     * Returns True if the friendshipId lists have a common element.
     * Linear time complexity w.r.t. set sizes.
     */
    private static boolean areConnected(Set<String> setA, Set<String> setB) {
        return setB.stream().anyMatch(setA::contains);
    }

    private static Map<String, Collection<String>> reduceFriendships(int startID, int endID) {
        Map<String, Collection<String>> result = new HashMap<>(Collections.emptyMap());

        Set<String> startFriendships = new HashSet<>(Collections.emptySet());
        startFriendships.add(String.valueOf(startID));

        Set<String> endFriendships = new HashSet<>(Collections.emptyList());
        endFriendships.add(String.valueOf(endID));

        do {

            //StartFreundeskreis := alle Nutzer , die von startID in i Schritten erreichbar sind;
            for (String startFriend: startFriendships) {
                List<String> tmp_friendships = getFriends(Integer.parseInt(startFriend));

                result.put(startFriend, tmp_friendships);
                startFriendships.addAll(tmp_friendships);
            }

            //EndFreundeskreis := alle Nutzer , die von endID in i Schritten erreichbar sind;
            for (String endFriend: endFriendships) {
                List<String> tmp_friendships = getFriends(Integer.parseInt(endFriend));

                result.put(endFriend, tmp_friendships);
                endFriendships.addAll(tmp_friendships);
            }
        } while (!areConnected(startFriendships, endFriendships));

        return result;
    }
}
