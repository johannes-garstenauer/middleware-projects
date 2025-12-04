package mw.mapreduce.jobs.friendsort;

import java.util.Comparator;

public class MWFriendSortComparator implements Comparator<String> {

    @Override
    public int compare(String o1, String o2) {
        try {
            return Integer.compare(Integer.parseInt(o2), Integer.parseInt(o1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Both strings must be valid integers.", e);
        }
    }
}
