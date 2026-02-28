package lld.designpatterns.iterator;

import java.util.Iterator;
import java.util.Objects;

/**
 * Collection that supports multiple traversal strategies (in-order, pre-order, BFS) via iterators.
 */
public interface TreeCollection<T> {

    Iterator<T> iterator(TraversalOrder order);

    enum TraversalOrder {
        PRE_ORDER, IN_ORDER, BREADTH_FIRST
    }
}
