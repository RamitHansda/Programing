package lld.filedirectory;

import java.util.*;

public class FileDirectory {
    public static void main(String[] args) {
//        FileSystem obj = new FileSystem();
//        String path = "ramit/test";
//        List<String> param_1 = obj.ls(path);
//        obj.mkdir(path);
//        //obj.addContentToFile(filePath,content);
//        //String param_4 = obj.readContentFromFile(filePath);
//        System.out.println(obj);

        FileSystem fileSystem = new FileSystem();
        fileSystem.ls("/"); // return []
        fileSystem.mkdir("/a/b/c");
        fileSystem.addContentToFile("/a/b/c/d", "hello");
        System.out.println(fileSystem.ls("/")); // return ["a"]
        fileSystem.readContentFromFile("/a/b/c/d"); // return "hello"

    }
}


class Trie {
    String name;                                    // File name (only used for files)
    boolean isFile;                                 // Flag to indicate if this node represents a file
    StringBuilder content = new StringBuilder();    // File content (only used for files)
    Map<String, Trie> children = new HashMap<>();  // Map of child directories/files

    /**
     * Inserts a new path into the trie structure
     * @param path The absolute path to insert
     * @param isFile Whether the path represents a file or directory
     * @return The node representing the inserted path
     */
    Trie insert(String path, boolean isFile) {
        Trie currentNode = this;
        String[] pathComponents = path.split("/");

        // Start from index 1 to skip empty string before first "/"
        for (int i = 1; i < pathComponents.length; ++i) {
            String component = pathComponents[i];

            // Create new node if path component doesn't exist
            if (!currentNode.children.containsKey(component)) {
                currentNode.children.put(component, new Trie());
            }
            currentNode = currentNode.children.get(component);
        }

        // Mark as file and store name if this is a file
        currentNode.isFile = isFile;
        if (isFile) {
            currentNode.name = pathComponents[pathComponents.length - 1];
        }

        return currentNode;
    }

    /**
     * Searches for a node at the given path
     * @param path The absolute path to search
     * @return The node at the path, or null if not found
     */
    Trie search(String path) {
        Trie currentNode = this;
        String[] pathComponents = path.split("/");

        // Start from index 1 to skip empty string before first "/"
        for (int i = 1; i < pathComponents.length; ++i) {
            String component = pathComponents[i];

            // Return null if path doesn't exist
            if (!currentNode.children.containsKey(component)) {
                return null;
            }
            currentNode = currentNode.children.get(component);
        }

        return currentNode;
    }
}

/**
 * In-memory file system implementation using Trie data structure
 */
class FileSystem {
    private Trie root = new Trie();  // Root of the file system tree

    /**
     * Constructor initializes an empty file system
     */
    public FileSystem() {
    }

    /**
     * Lists the contents of a directory or returns file name if path is a file
     * @param path The absolute path to list
     * @return List of file/directory names in lexicographic order
     */
    public List<String> ls(String path) {
        List<String> result = new ArrayList<>();

        // Handle root path special case
        Trie targetNode = root.search(path);
        if (targetNode == null) {
            return result;
        }

        // If path points to a file, return only the file name
        if (targetNode.isFile) {
            result.add(targetNode.name);
            return result;
        }

        // If path points to a directory, list all children
        for (String childName : targetNode.children.keySet()) {
            result.add(childName);
        }

        // Sort results lexicographically
        Collections.sort(result);
        return result;
    }

    /**
     * Creates a new directory at the given path
     * @param path The absolute path of the directory to create
     */
    public void mkdir(String path) {
        root.insert(path, false);
    }

    /**
     * Adds content to a file, creating the file if it doesn't exist
     * @param filePath The absolute path of the file
     * @param content The content to append to the file
     */
    public void addContentToFile(String filePath, String content) {
        Trie fileNode = root.insert(filePath, true);
        fileNode.content.append(content);
    }

    /**
     * Reads and returns the content of a file
     * @param filePath The absolute path of the file to read
     * @return The content of the file as a string
     */
    public String readContentFromFile(String filePath) {
        Trie fileNode = root.search(filePath);
        return fileNode.content.toString();
    }
}

/**
 * Your FileSystem object will be instantiated and called as such:
 * FileSystem obj = new FileSystem();
 * List<String> param_1 = obj.ls(path);
 * obj.mkdir(path);
 * obj.addContentToFile(filePath,content);
 * String param_4 = obj.readContentFromFile(filePath);
 */