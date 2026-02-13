package lld.keyvalue;



import java.util.*;

/**
 * Custom Key-Value Store
 *
 * Level 1: get / set
 * Level 2: prefix search
 * Level 3: TTL support
 * Level 4: backup & restore
 */
public class KeyValueStore {

    /* ===================== DATA MODELS ===================== */

    static class Entry {
        String value;
        Long expiryTime; // null if no TTL

        Entry(String value, Long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
        }

        boolean isExpired(long timestamp) {
            return expiryTime != null && timestamp >= expiryTime;
        }
    }

    static class TrieNode {
        Map<Character, TrieNode> children = new HashMap<>();
        boolean isEnd;
    }

    /* ===================== STORAGE ===================== */

    // Main key-value store
    private final Map<String, Entry> store = new HashMap<>();

    // Trie for prefix search
    private TrieNode root = new TrieNode();

    // Backups: backupId -> snapshot
    private final Map<Integer, Map<String, Entry>> backups = new HashMap<>();
    private int backupCounter = 0;

    /* ===================== LEVEL 1 ===================== */

    /**
     * Set a key with optional TTL
     */
    public void set(String key, String value, Long ttl, long timestamp) {
        Long expiry = (ttl == null) ? null : timestamp + ttl;
        store.put(key, new Entry(value, expiry));
        insertIntoTrie(key);
    }

    /**
     * Get value at a given timestamp
     */
    public String get(String key, long timestamp) {
        Entry entry = store.get(key);
        if (entry == null) return null;

        if (entry.isExpired(timestamp)) {
            store.remove(key); // lazy cleanup
            return null;
        }
        return entry.value;
    }

    /* ===================== LEVEL 2 ===================== */

    /**
     * Prefix search at a given timestamp
     */
    public List<String> prefixSearch(String prefix, long timestamp) {
        List<String> result = new ArrayList<>();
        TrieNode node = root;

        for (char c : prefix.toCharArray()) {
            if (!node.children.containsKey(c)) {
                return result;
            }
            node = node.children.get(c);
        }

        dfs(node, new StringBuilder(prefix), result, timestamp);
        return result;
    }

    /* ===================== LEVEL 3 ===================== */
    // TTL handled lazily in get() and prefixSearch()

    /* ===================== LEVEL 4 ===================== */

    /**
     * Backup the store state at a given timestamp
     */
    public int backup(long timestamp) {
        Map<String, Entry> snapshot = new HashMap<>();

        for (Map.Entry<String, Entry> e : store.entrySet()) {
            if (!e.getValue().isExpired(timestamp)) {
                snapshot.put(
                        e.getKey(),
                        new Entry(e.getValue().value, e.getValue().expiryTime)
                );
            }
        }

        int backupId = ++backupCounter;
        backups.put(backupId, snapshot);
        return backupId;
    }

    /**
     * Restore the store from a backup
     */
    public void restore(int backupId) {
        Map<String, Entry> snapshot = backups.get(backupId);
        if (snapshot == null) return;

        store.clear();
        root = new TrieNode();

        for (Map.Entry<String, Entry> e : snapshot.entrySet()) {
            store.put(e.getKey(), e.getValue());
            insertIntoTrie(e.getKey());
        }
    }

    /* ===================== HELPERS ===================== */

    private void insertIntoTrie(String key) {
        TrieNode node = root;
        for (char c : key.toCharArray()) {
            node.children.putIfAbsent(c, new TrieNode());
            node = node.children.get(c);
        }
        node.isEnd = true;
    }

    private void dfs(TrieNode node, StringBuilder path,
                     List<String> result, long timestamp) {

        if (node.isEnd) {
            String key = path.toString();
            Entry entry = store.get(key);
            if (entry != null && !entry.isExpired(timestamp)) {
                result.add(key);
            }
        }

        for (Map.Entry<Character, TrieNode> e : node.children.entrySet()) {
            path.append(e.getKey());
            dfs(e.getValue(), path, result, timestamp);
            path.deleteCharAt(path.length() - 1);
        }
    }

    /* ===================== DEMO ===================== */

    public static void main(String[] args) {
        KeyValueStore kv = new KeyValueStore();

        // Level 1 + 3
        kv.set("apple", "fruit", 5L, 10);
        kv.set("app", "short", null, 10);

        System.out.println(kv.get("apple", 12)); // fruit
        System.out.println(kv.get("apple", 16)); // null (expired)

        // Level 2
        System.out.println(kv.prefixSearch("ap", 12)); // [apple, app]

        // Level 4
        int backupId = kv.backup(12);

        kv.set("apricot", "fruit", null, 13);
        System.out.println(kv.prefixSearch("ap", 14)); // [apple, app, apricot]

        kv.restore(backupId);
        System.out.println(kv.prefixSearch("ap", 14)); // [apple, app]
    }
}
