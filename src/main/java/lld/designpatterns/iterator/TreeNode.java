package lld.designpatterns.iterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tree node for iterator demo. Supports multiple traversal strategies via iterators.
 */
public final class TreeNode<T> {

    private final T value;
    private final List<TreeNode<T>> children = new ArrayList<>();

    public TreeNode(T value) {
        this.value = Objects.requireNonNull(value);
    }

    public T getValue() { return value; }
    public List<TreeNode<T>> getChildren() { return List.copyOf(children); }
    public void addChild(TreeNode<T> child) { children.add(Objects.requireNonNull(child)); }
}
