package lld.multilistiterator;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * An iterator that traverses multiple lists sequentially:
 * exhausts list[0], then list[1], then list[2], …
 *
 * <p>Complexity: hasNext O(1) amortised, next O(1) amortised, space O(1) extra.
 *
 * @param <T> the element type
 */
public class MultiListIterator<T> implements Iterator<T> {

    private final List<List<T>> lists;
    private int outerIndex;          // which sub-list we are currently in
    private int innerIndex;          // position inside that sub-list

    public MultiListIterator(List<List<T>> lists) {
        if (lists == null) throw new IllegalArgumentException("lists must not be null");
        this.lists = lists;
        this.outerIndex = 0;
        this.innerIndex = 0;
        advancePastEmpty();
    }

    /** Skips over any exhausted (or empty) sub-lists so that outerIndex always
     *  points to a sub-list that still has elements, or goes past the end. */
    private void advancePastEmpty() {
        while (outerIndex < lists.size()
                && (lists.get(outerIndex) == null
                || innerIndex >= lists.get(outerIndex).size())) {
            outerIndex++;
            innerIndex = 0;
        }
    }

    @Override
    public boolean hasNext() {
        return outerIndex < lists.size();
    }

    @Override
    public T next() {
        if (!hasNext()) throw new NoSuchElementException("No more elements");
        T value = lists.get(outerIndex).get(innerIndex);
        innerIndex++;
        advancePastEmpty();
        return value;
    }
}
