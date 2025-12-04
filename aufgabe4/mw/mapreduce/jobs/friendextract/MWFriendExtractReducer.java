package mw.mapreduce.jobs.friendextract;

import mw.mapreduce.core.MWContext;
import mw.mapreduce.core.MWReducer;
import java.util.HashSet;
import java.util.Set;

public class MWFriendExtractReducer extends MWReducer {

    @Override
    protected void reduce(String key, Iterable<String> values, MWContext<?> context) throws Exception {
        Set<String> uniqueFriends = new HashSet<>();

        for (String friend : values) {
            // Deduplizierung: Falls Freundschaften doppelt gemeldet werden
            if (uniqueFriends.add(friend)) {
                // Ausgabe: ID_A \t ID_B
                context.write(key, friend);
            }
        }
    }
}