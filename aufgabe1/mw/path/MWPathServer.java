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
     */
    private static boolean areConnected(List<String> listA, List<String> listB) {
        Set<String> set = new HashSet<>(listA);

        return listB.stream().anyMatch(set::contains);
    }

    // TODO edge cases: -> sollten passen
    //  1) start = end
    //  2) path is just 1 person long
    private static Map<String, Collection<String>> reduceFriendships(int startID, int endID) {
        Set<String> startFriendships = new HashSet<>(Collections.emptySet());
        startFriendships.add(String.valueOf(startID));

        List<String> endFriendships = new ArrayList<>(Collections.emptyList());
        endFriendships.add(String.valueOf(endID));
/*
        while (!areConnected(startFriendships, endFriendships)) {

            //StartFreundeskreis := alle Nutzer , die von startID in i Schritten erreichbar sind;
            for (String startFriend: startFriendships) {
                //startFriendships.addAll(getFriends(String.valueOf(startFriend));
            }

            //EndFreundeskreis := alle Nutzer , die von endID in i Schritten erreichbar sind;
            for (String endFriend: endFriendships) {
                //endFriendships.addAll(getFriends(String.valueOf(endFriend)));
            }
        }*/
        //return startFriendships.addAll(endFriendships);
        return null;
    }
}
