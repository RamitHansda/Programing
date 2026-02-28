package lld.designpatterns.iterator;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Objects;

/**
 * Tree collection with multiple traversal iterators. Clients use Iterator interface only.
 */
public final class SimpleTreeCollection<T> implements TreeCollection<T> {

    private final TreeNode<T> root;

    public SimpleTreeCollection(TreeNode<T> root) {
        this.root = Objects.requireNonNull(root);
    }

    @Override
    public Iterator<T> iterator(TraversalOrder order) {
        return switch (order) {
            case PRE_ORDER -> new PreOrderIterator<>(root);
            case IN_ORDER -> new InOrderIterator<>(root);
            case BREADTH_FIRST -> new BreadthFirstIterator<>(root);
        };
    }

    private static final class PreOrderIterator<T> implements Iterator<T> {
        private final java.util.Stack<TreeNode<T>> stack = new java.util.Stack<>();

        PreOrderIterator(TreeNode<T> root) {
            if (root != null) stack.push(root);
        }

        @Override
        public boolean hasNext() { return !stack.isEmpty(); }

        @Override
        public T next() {
            TreeNode<T> n = stack.pop();
            for (int i = n.getChildren().size() - 1; i >= 0; i--) {
                stack.push(n.getChildren().get(i));
            }
            return n.getValue();
        }
    }

    private static final class InOrderIterator<T> implements Iterator<T> {
        private final java.util.Stack<TreeNode<T>> stack = new java.util.Stack<>();
        private TreeNode<T> current;

        InOrderIterator(TreeNode<T> root) {
            current = root;
            pushLeft();
        }

        private void pushLeft() {
            while (current != null) {
                stack.push(current);
                var children = current.getChildren();
                current = children.isEmpty() ? null : children.get(0);
            }
        }

        @Override
        public boolean hasNext() { return !stack.isEmpty(); }

        @Override
        public T next() {
            TreeNode<T> n = stack.pop();
            var children = n.getChildren();
            current = children.size() > 1 ? children.get(1) : null;
            pushLeft();
            return n.getValue();
        }
    }

    private static final class BreadthFirstIterator<T> implements Iterator<T> {
        private final Queue<TreeNode<T>> queue = new LinkedList<>();

        BreadthFirstIterator(TreeNode<T> root) {
            if (root != null) queue.offer(root);
        }

        @Override
        public boolean hasNext() { return !queue.isEmpty(); }

        @Override
        public T next() {
            TreeNode<T> n = queue.poll();
            for (TreeNode<T> c : n.getChildren()) {
                queue.offer(c);
            }
            return n.getValue();
        }
    }
}
