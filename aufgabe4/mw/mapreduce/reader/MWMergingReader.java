package mw.mapreduce.reader;

import mw.mapreduce.util.MWPair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;



public class MWMergingReader implements MWReader<MWPair<String, String>> {
    //static class MWListComparator implements Comparator<ArrayList<MWPair<String, String>>> {
    //    public int compare(ArrayList<MWPair<String, String>> l1, ArrayList<MWPair<String, String>> l2) {
    //        return l1.getFirst().getKey().compareTo(l2.getFirst().getKey());
    //    }
    //}

    private final PriorityQueue<ArrayList<MWPair<String, String>>> queue;
    public MWMergingReader(Comparator<? super MWPair<String, String>> pairComparator) {
        // Comparator that lifts a pair-comparator to a "list of pairs" comparator
        Comparator<ArrayList<MWPair<String, String>>> listComparator =
                (l1, l2)
                        -> pairComparator.compare(l1.getFirst(), l2.getFirst());

        this.queue = new PriorityQueue<>(listComparator);
    }

    @Override
    public MWPair<String, String> read() {
        if (queue.isEmpty()) {return null;}
        ArrayList<MWPair<String, String>> pairs = queue.poll();
        MWPair<String, String> head = pairs.removeFirst();
        if (!pairs.isEmpty()) {
            queue.add(pairs);
        }
        return head;
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
