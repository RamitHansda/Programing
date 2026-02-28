package lld.designpatterns.composite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Composite: directory aggregates children; size = sum of children's sizes.
 */
public final class DirectoryNode implements FileSystemNode {

    private final String name;
    private final List<FileSystemNode> children = new ArrayList<>();

    public DirectoryNode(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public void addChild(FileSystemNode node) {
        children.add(Objects.requireNonNull(node));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getSize() {
        return children.stream().mapToLong(FileSystemNode::getSize).sum();
    }

    @Override
    public List<FileSystemNode> getChildren() {
        return List.copyOf(children);
    }

    @Override
    public Optional<FileSystemNode> find(String name) {
        if (this.name.equals(name)) {
            return Optional.of(this);
        }
        for (FileSystemNode child : children) {
            Optional<FileSystemNode> found = child.find(name);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isDirectory() {
        return true;
    }
}
