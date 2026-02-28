package lld.designpatterns.composite;

import java.util.List;
import java.util.Optional;

/**
 * Composite: common interface for files and directories. Uniform getSize, list, find.
 */
public interface FileSystemNode {

    String getName();
    long getSize();
    List<FileSystemNode> getChildren();
    Optional<FileSystemNode> find(String name);

    /** True if this node can have children (directory). */
    boolean isDirectory();
}
