package lld.multilistiterator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * An iterator that traverses multiple lists in round-robin order:
 * picks one element from each non-exhausted list in turn, cycling
 * until all lists are exhausted.
 *
 * <p>Example – lists [[1,2,3],[4,5],[6]] produces 1,4,6,2,5,3.
 *
 * <p>Implementation uses a deque of per-list iterators; once a sub-iterator
 * is exhausted it is discarded.
 *
 * <p>Complexity: hasNext O(1), next O(1) amortised, space O(k) where k = number of lists.
 *
 * @param <T> the element type
 */
public class RoundRobinMultiListIterator<T> implements Iterator<T> {

    private final Deque<Iterator<T>> queue = new ArrayDeque<>();

    public RoundRobinMultiListIterator(List<List<T>> lists) {
        if (lists == null) throw new IllegalArgumentException("lists must not be null");
        for (List<T> list : lists) {
            if (list != null && !list.isEmpty()) {
                queue.addLast(list.iterator());
            }
        }
    }

    @Override
    public boolean hasNext() {
        return !queue.isEmpty();
    }

    @Override
    public T next() {
        if (!hasNext()) throw new NoSuchElementException("No more elements");

        Iterator<T> current = queue.pollFirst();
        T value = current.next();

        // Re-enqueue only if this sub-iterator still has elements
        if (current.hasNext()) {
            queue.addLast(current);
        }

        return value;
    }
}
