package lld.filesystem;

import java.util.Optional;

/**
 * Composite node in the in-memory file system tree.
 */
public interface FsNode {
    String name();

    Optional<DirectoryNode> parent();

    void setParent(DirectoryNode parent);

    boolean isDirectory();
}
