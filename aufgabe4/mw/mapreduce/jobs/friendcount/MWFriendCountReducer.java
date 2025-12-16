package mw.mapreduce.jobs.friendcount;

import mw.mapreduce.core.MWContext;
import mw.mapreduce.core.MWReducer;

public class MWFriendCountReducer extends MWReducer {

    @Override
    protected void reduce(String key, Iterable<String> values, MWContext<?> context) throws Exception {
        int friendCount = 0;
        String realName = null;

        for (String val : values) {
            if (val.startsWith("NAME:")) {
                // Klarname
                realName = val.substring(5); // "NAME:" abschneiden
            } else if (val.equals("COUNT")) {
                // Freund
                friendCount++;
            }
        }

        if (realName != null) {
            context.write(realName, String.valueOf(friendCount));
        }
    }
}