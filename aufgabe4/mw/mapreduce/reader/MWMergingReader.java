package mw.mapreduce.reader;

import mw.mapreduce.util.MWPair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Iterator;


public class MWMergingReader implements MWReader<MWPair<String, String>> {

    // Wrapper class to hold an iterator and its current element
    private static class PartitionIterator {
        private final Iterator<MWPair<String, String>> iterator;
        private MWPair<String, String> current;

        public PartitionIterator(ArrayList<MWPair<String, String>> pairs) {
            this.iterator = pairs.iterator();
            this.current = iterator.hasNext() ? iterator.next() : null;
        }

        public MWPair<String, String> getCurrent() {
            return current;
        }

        public boolean advance() {
            if (iterator.hasNext()) {
                current = iterator.next();
                return true;
            }
            return false;
        }

        public boolean hasElement() {
            return current != null;
        }
    }

    private final PriorityQueue<PartitionIterator> queue;

    public MWMergingReader(Comparator<? super MWPair<String, String>> pairComparator) {
        // Comparator that compares the current elements of partition iterators
        Comparator<PartitionIterator> iteratorComparator =
                (pi1, pi2) -> pairComparator.compare(pi1.getCurrent(), pi2.getCurrent());

        this.queue = new PriorityQueue<>(iteratorComparator);
    }

    @Override
    public MWPair<String, String> read() {
        if (queue.isEmpty()) {
            return null;
        }

        PartitionIterator partitionIter = queue.poll();
        MWPair<String, String> result = partitionIter.getCurrent();

        // Advance to next element and re-add to queue if more elements exist
        if (partitionIter.advance()) {
            queue.add(partitionIter);
        }

        return result;
    }

    public void addToQueue(ArrayList<MWPair<String, String>> pairs) {
        // List is assumed to be sorted
        if (pairs == null || pairs.isEmpty()) {
            return;
        }
        queue.add(new PartitionIterator(pairs));
    }

    public ArrayList<MWPair<String, String>> getMergedList() {
        ArrayList<MWPair<String, String>> result = new ArrayList<>();
        MWPair<String, String> pair;
        while ((pair = read()) != null) {
            result.add(pair);
        }
        return result;
    }
}
