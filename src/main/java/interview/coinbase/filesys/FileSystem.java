package interview.coinbase.filesys;

import java.util.*;

public class FileSystem {
    private final Trie root;
    private String currentPath;
    private Trie currentNode;

    FileSystem() {
        this.root = new Trie();
        this.root.name = "/";
        this.currentPath = "/";
        this.currentNode = root;
    }

    /**
     * Resolves a path against the current working directory.
     * Handles absolute paths (starting with /), relative paths, dot (.) and dot-dot (..).
     */
    public String resolvePath(String path) {
        String combined = path.startsWith("/") ? path : currentPath + "/" + path;

        String[] segments = combined.split("/");
        Deque<String> stack = new ArrayDeque<>();

        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            } else if (segment.equals("..")) {
                if (!stack.isEmpty()) stack.pollLast();
            } else {
                stack.addLast(segment);
            }
        }

        if (stack.isEmpty()) return "/";

        StringBuilder sb = new StringBuilder();
        for (String seg : stack) sb.append("/").append(seg);
        return sb.toString();
    }

    /**
     * Creates a directory (and all necessary parent directories) at the given path.
     * Supports both absolute and relative paths.
     */
    public void mkdir(String path) {
        String resolved = resolvePath(path);
        root.insert(resolved, false);
    }

    /**
     * Returns the absolute path of the current working directory.
     */
    public String pwd() {
        return currentPath;
    }

    /**
     * Changes the current working directory to the specified path.
     * Supports absolute paths, relative paths, . and ..
     *
     * @throws IllegalArgumentException if the path does not exist or is a file
     */
    public void cd(String path) {
        String resolved = resolvePath(path);
        Trie target = resolved.equals("/") ? root : root.search(resolved);

        if (target == null) {
            throw new IllegalArgumentException("cd: " + path + ": No such file or directory");
        }
        if (target.isFile) {
            throw new IllegalArgumentException("cd: " + path + ": Not a directory");
        }

        currentPath = resolved;
        currentNode = target;
    }

    public void createFile(String path) {
        String resolved = resolvePath(path);
        root.insert(resolved, true);
    }

    public List<Trie> ls(String path) {
        List<Trie> result = new ArrayList<>();
        String resolved = resolvePath(path);
        Trie searchedVal = resolved.equals("/") ? root : root.search(resolved);

        if (searchedVal == null) return result;

        if (searchedVal.isFile) {
            result.add(searchedVal);
            return result;
        }

        for (Map.Entry<String, Trie> entry : searchedVal.children.entrySet()) {
            result.add(entry.getValue());
        }

        Collections.sort(result, Comparator.comparingInt((Trie t) -> t.size).reversed());
        return result;
    }
}
