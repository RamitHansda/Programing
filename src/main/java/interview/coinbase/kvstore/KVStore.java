package interview.coinbase.kvstore;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public  class KVStore {

    static class Entry{
        String value;
        Long expiredAt;

        Entry(String value, long expiredAt){
            this.value = value;
            this.expiredAt = expiredAt;
        }

        boolean isExpired(long timestamp){
            return this.expiredAt != null && (this.expiredAt < timestamp);
        }
    }

    static class TrieNode {
        Map<Character, TrieNode> children = new HashMap<>();
        boolean isEnd;
    }

    private TrieNode root = new TrieNode();

    private void insertIntoTrie(String key){
        TrieNode node = root;
        for (char c: key.toCharArray()){
            node.children.putIfAbsent(c, new TrieNode());
            node = node.children.get(c);
        }
        node.isEnd = true;
    }

    public List<String> prefixSearch(String prefix, long timestamp) {
        List<String> result = new ArrayList<>();
        TrieNode node = root;
        for (char c: prefix.toCharArray()){
            if(!node.children.containsKey(c))
                return result;
            node = node.children.get(c);
        }
        dfs(node, new StringBuilder(prefix), timestamp, result);
        return result;
    }

    private void dfs(TrieNode node, StringBuilder prefix, long timestamp, List<String> result){
        if(node.isEnd){
            String key = prefix.toString();
            Entry entry = store.get(key);
            if(entry != null && !entry.isExpired(timestamp)){
                result.add(prefix.toString());
            }
        }

        for (Map.Entry<Character, TrieNode> entry: node.children.entrySet()){
            prefix.append(entry.getKey());
            dfs(entry.getValue(), prefix, timestamp, result);
            prefix.deleteCharAt(prefix.length()-1);
        }
    }


    HashMap<String, Entry> store = new HashMap<>();

    HashMap<Integer, Map<String, Entry>> snapshot = new HashMap<>();
    private int backupCounter = 0;


    public void put(String key, String value, long timestamp, Long ttl){
        Long expiresAt = (ttl==null) ? null : timestamp + ttl;
        store.put(key, new Entry(value, expiresAt));
        insertIntoTrie(key);
    }

    public String get(String key, long timestamp){
        Entry entry =  store.get(key);
        if(entry==null)
            return null;
        if(entry.isExpired(timestamp)){
            store.remove(key);
            return null;
        }
        return entry.value;
    }



    public int createBackUp(long timestamp){
        HashMap<String, Entry> snapStore = new HashMap<>();
        for (Map.Entry<String, Entry> entry: store.entrySet()){
            if(!entry.getValue().isExpired(timestamp))
                snapStore.put(entry.getKey(), entry.getValue());
        }

        int backupId = ++backupCounter;
        snapshot.put(backupId, snapStore);
        return backupId;
    }

    public void restore(int backUpId){
        Map<String, Entry>  snapStore = snapshot.get(backUpId);
        if(snapStore==null)
            return;

        root = new TrieNode();

        store.clear();
        for (Map.Entry<String, Entry> entry: snapStore.entrySet()){
            store.put(entry.getKey(), entry.getValue());
            insertIntoTrie(entry.getKey());
        }
    }






}
