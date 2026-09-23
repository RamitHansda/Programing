package lld.filesystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * In-memory hierarchical file system with a current working directory.
 *
 * <p>Primary shell-style API:
 * <ul>
 *   <li>{@link #cd(String)} — change directory (absolute or relative; supports {@code .} and {@code ..})</li>
 *   <li>{@link #pwd()} — print working directory</li>
 *   <li>{@link #ls(String)} / {@link #ls()} — list directory or file name</li>
 *   <li>{@link #mkdir(String)} — create directory (parents as needed)</li>
 *   <li>{@link #touch(String)} / {@link #write(String, String)} / {@link #read(String)} — file ops</li>
 * </ul>
 *
 * <p>Paths may be absolute ({@code /a/b}) or relative to the current working directory.
 */
public final class FileSystem {
    private final DirectoryNode root;
    private Path cwd;

    public FileSystem() {
        this.root = new DirectoryNode("/");
        this.cwd = Path.root();
    }

    /** Change the current working directory. Throws if the path is missing or is a file. */
    public void cd(String path) {
        Resolved target = resolveExisting(path);
        if (!target.node().isDirectory()) {
            throw new FileSystemException("not a directory: " + target.path());
        }
        this.cwd = target.path();
    }

    /** Absolute path of the current working directory. */
    public String pwd() {
        return cwd.toAbsolutePath();
    }

    /** List the current working directory. */
    public List<String> ls() {
        return ls(".");
    }

    /**
     * List a directory's children (sorted), or return a single-element list with the file name
     * when {@code path} points at a file.
     */
    public List<String> ls(String path) {
        Resolved target = resolveExisting(path);
        if (!target.node().isDirectory()) {
            return List.of(target.node().name());
        }
        DirectoryNode dir = (DirectoryNode) target.node();
        List<String> names = new ArrayList<>(dir.childNames());
        Collections.sort(names);
        return names;
    }

    /**
     * Create a directory at {@code path}, creating missing parents (mkdir -p semantics).
     * No-op if the directory already exists. Fails if a file occupies part of the path.
     */
    public void mkdir(String path) {
        Path absolute = Path.resolve(cwd, path);
        if (absolute.isRoot()) {
            return;
        }
        DirectoryNode current = root;
        List<String> segments = absolute.segments();
        for (int i = 0; i < segments.size(); i++) {
            String name = segments.get(i);
            Optional<FsNode> child = current.getChild(name);
            if (child.isEmpty()) {
                DirectoryNode created = new DirectoryNode(name);
                current.addChild(created);
                current = created;
            } else if (!child.get().isDirectory()) {
                throw new FileSystemException("not a directory: " + absolute);
            } else {
                current = (DirectoryNode) child.get();
            }
        }
    }

    /** Create an empty file (or leave existing file untouched). Parents must exist. */
    public void touch(String path) {
        Path absolute = Path.resolve(cwd, path);
        if (absolute.isRoot()) {
            throw new FileSystemException("cannot create file at root");
        }
        DirectoryNode parent = requireDirectory(absolute.parent());
        String name = absolute.leafName();
        Optional<FsNode> existing = parent.getChild(name);
        if (existing.isPresent()) {
            if (existing.get().isDirectory()) {
                throw new FileSystemException("is a directory: " + absolute);
            }
            return;
        }
        parent.addChild(new FileNode(name));
    }

    /** Append content to a file, creating it if missing (parents must exist). */
    public void write(String path, String content) {
        Path absolute = Path.resolve(cwd, path);
        DirectoryNode parent = requireDirectory(absolute.parent());
        String name = absolute.leafName();
        FsNode node = parent.getChild(name).orElse(null);
        if (node == null) {
            FileNode created = new FileNode(name);
            parent.addChild(created);
            created.append(content);
            return;
        }
        if (node.isDirectory()) {
            throw new FileSystemException("is a directory: " + absolute);
        }
        ((FileNode) node).append(content);
    }

    /** Read file content. */
    public String read(String path) {
        Resolved target = resolveExisting(path);
        if (target.node().isDirectory()) {
            throw new FileSystemException("is a directory: " + target.path());
        }
        return ((FileNode) target.node()).read();
    }

    /** Delete a file or empty directory. Root cannot be deleted. */
    public void rm(String path) {
        Path absolute = Path.resolve(cwd, path);
        if (absolute.isRoot()) {
            throw new FileSystemException("cannot remove root");
        }
        DirectoryNode parent = requireDirectory(absolute.parent());
        FsNode removed = parent.removeChild(absolute.leafName());
        if (removed.isDirectory() && !((DirectoryNode) removed).childNames().isEmpty()) {
            parent.addChild(removed);
            throw new FileSystemException("directory not empty: " + absolute);
        }
        if (isSameOrDescendant(absolute, cwd)) {
            this.cwd = Path.root();
        }
    }

    private static boolean isSameOrDescendant(Path ancestor, Path candidate) {
        String a = ancestor.toAbsolutePath();
        String c = candidate.toAbsolutePath();
        if ("/".equals(a)) {
            return true;
        }
        return c.equals(a) || c.startsWith(a + "/");
    }

    public Path currentPath() {
        return cwd;
    }

    private Resolved resolveExisting(String raw) {
        Path absolute = Path.resolve(cwd, raw);
        FsNode node = lookup(absolute)
                .orElseThrow(() -> new FileSystemException("no such file or directory: " + absolute));
        return new Resolved(absolute, node);
    }

    private DirectoryNode requireDirectory(Path absolute) {
        FsNode node = lookup(absolute)
                .orElseThrow(() -> new FileSystemException("no such file or directory: " + absolute));
        if (!node.isDirectory()) {
            throw new FileSystemException("not a directory: " + absolute);
        }
        return (DirectoryNode) node;
    }

    private Optional<FsNode> lookup(Path absolute) {
        if (absolute.isRoot()) {
            return Optional.of(root);
        }
        DirectoryNode current = root;
        List<String> segments = absolute.segments();
        for (int i = 0; i < segments.size(); i++) {
            Optional<FsNode> child = current.getChild(segments.get(i));
            if (child.isEmpty()) {
                return Optional.empty();
            }
            if (i == segments.size() - 1) {
                return child;
            }
            if (!child.get().isDirectory()) {
                return Optional.empty();
            }
            current = (DirectoryNode) child.get();
        }
        return Optional.of(current);
    }

    private record Resolved(Path path, FsNode node) {
    }
}
