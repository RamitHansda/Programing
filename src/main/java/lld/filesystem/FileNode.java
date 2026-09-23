package lld.filesystem;

import java.util.Optional;

/**
 * File leaf node holding string content.
 */
public final class FileNode implements FsNode {
    private final String name;
    private DirectoryNode parent;
    private final StringBuilder content = new StringBuilder();

    public FileNode(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("file name must be non-empty");
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
        return false;
    }

    public void append(String data) {
        if (data != null) {
            content.append(data);
        }
    }

    public String read() {
        return content.toString();
    }

    public int size() {
        return content.length();
    }
}
