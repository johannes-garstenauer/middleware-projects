package mw.mapreduce.jobs.friendsort;

import mw.mapreduce.core.MWContext;
import mw.mapreduce.core.MWMapper;

public class MWFriendSortMapper extends MWMapper {

    @Override
    public void map(String key, String value, MWContext context) throws Exception {
        context.write(value, key);
    }
}
