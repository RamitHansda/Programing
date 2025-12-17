package lld.lrucache;



class LRUCacheDemo {
    public static void main(String[] args) {
        LRUCache<String, Integer> cache = new LRUCache<>(3);

        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        // Accessing 'a' makes it the most recently used
        System.out.println(cache.get("a")); // 1

        // Adding 'd' will cause 'b' (the current LRU item) to be evicted
        cache.put("d", 4);

        // Trying to get 'a' should now return null
        System.out.println(cache.get("a")); // null
    }
}