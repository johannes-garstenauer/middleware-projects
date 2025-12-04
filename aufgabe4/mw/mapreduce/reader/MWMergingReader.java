package mw.mapreduce.reader;

import mw.mapreduce.util.MWPair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;



public class MWMergingReader {
    static class MWListComparator implements Comparator<ArrayList<MWPair<String, String>>> {
        public int compare(ArrayList<MWPair<String, String>> l1, ArrayList<MWPair<String, String>> l2) {
            return l1.getFirst().getKey().compareTo(l2.getFirst().getKey());
        }
    }

    private final Comparator<ArrayList<MWPair<String, String>>> comparator = new MWListComparator();
    private final PriorityQueue<ArrayList<MWPair<String, String>>> queue =
            new PriorityQueue<ArrayList<MWPair<String, String>>>(comparator);

    public MWMergingReader() {
    }

    public void addToQueue(ArrayList<MWPair<String, String>> pairs){
        // list is assumed to be sorted
        if (pairs == null || pairs.isEmpty()) {
            return;
        }
        queue.add(pairs);
    }

    public ArrayList<MWPair<String, String>> getMergedList() {
        ArrayList<MWPair<String, String>> result = new ArrayList<>();
        while (!queue.isEmpty()){
            ArrayList<MWPair<String, String>> pairs = queue.poll();
            result.add(pairs.getFirst());
            pairs.removeFirst();
            if (!pairs.isEmpty()) {
                queue.add(pairs);
            }
        }
        return result;
    }

}
