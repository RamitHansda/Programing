package interview.coinbase.filesys;

import java.util.HashMap;
import java.util.Random;

public class Trie {
    String name;
    int size;
    boolean isFile;
    Trie parent;
    HashMap<String, Trie> children = new HashMap<>();

    @Override
    public String toString() {
        return this.name + " " + ((this.isFile) ? "its a file with size " + this.size : "its a dir");
    }

    public Trie insert(String path, boolean isFile) {
        Trie current = this;
        String[] pathArray = path.split("/");
        for (int i = 1; i < pathArray.length; i++) {
            if (pathArray[i].isEmpty()) continue;
            if (current.children.get(pathArray[i]) == null) {
                Trie child = new Trie();
                child.name = pathArray[i];
                child.parent = current;
                current.children.put(pathArray[i], child);
            }
            current = current.children.get(pathArray[i]);
        }

        current.isFile = isFile;
        if (isFile) {
            current.size = new Random().nextInt(10);
        }
        return current;
    }

    public Trie search(String key) {
        Trie current = this;
        String[] keySplitArray = key.split("/");
        for (int i = 1; i < keySplitArray.length; i++) {
            if (keySplitArray[i].isEmpty()) continue;
            if (current.children.get(keySplitArray[i]) == null)
                return null;
            current = current.children.get(keySplitArray[i]);
        }
        return current;
    }
}
