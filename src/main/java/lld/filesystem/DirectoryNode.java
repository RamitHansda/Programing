package lld.filesystem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Directory node: owns child name → node mappings.
 */
public final class DirectoryNode implements FsNode {
    private final String name;
    private DirectoryNode parent;
    private final Map<String, FsNode> children = new LinkedHashMap<>();

    public DirectoryNode(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("directory name must be non-empty");
        }
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<DirectoryNode> parent() {
        return Optional.ofNullable(parent);
    }

    @Override
    public void setParent(DirectoryNode parent) {
        this.parent = parent;
    }

    @Override
    public boolean isDirectory() {
        return true;
    }

    public boolean hasChild(String childName) {
        return children.containsKey(childName);
    }

    public Optional<FsNode> getChild(String childName) {
        return Optional.ofNullable(children.get(childName));
    }

    public void addChild(FsNode node) {
        if (children.containsKey(node.name())) {
            throw new FileSystemException("already exists: " + node.name());
        }
        children.put(node.name(), node);
        node.setParent(this);
    }

    public FsNode removeChild(String childName) {
        FsNode removed = children.remove(childName);
        if (removed == null) {
            throw new FileSystemException("no such file or directory: " + childName);
        }
        removed.setParent(null);
        return removed;
    }

    public Set<String> childNames() {
        return Collections.unmodifiableSet(children.keySet());
    }

    public Map<String, FsNode> children() {
        return Collections.unmodifiableMap(children);
    }
}
