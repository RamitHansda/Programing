package lld.designpatterns.composite;

import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * Leaf: file with a fixed size.
 */
public final class FileNode implements FileSystemNode {

    private final String name;
    private final long size;

    public FileNode(String name, long size) {
        this.name = Objects.requireNonNull(name);
        this.size = size >= 0 ? size : 0;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public List<FileSystemNode> getChildren() {
        return List.of();
    }

    @Override
    public Optional<FileSystemNode> find(String name) {
        return this.name.equals(name) ? Optional.of(this) : Optional.empty();
    }

    @Override
    public boolean isDirectory() {
        return false;
    }
}
