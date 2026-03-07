package lld.ttl_hashmap;

import java.util.Comparator;
import java.util.concurrent.*;

public class TTLHashMap<K, V>{

    private static class CacheEntry<K,V>{
        final K key;
        final V value;
        final long expiryTime;
        CacheEntry(K key, V value, long expiryTime){
            this.key = key;
            this.value = value;
            this.expiryTime = expiryTime;
        }
    }

    private final ConcurrentHashMap<K, CacheEntry<K,V>> concurrentHashMap;
    private final PriorityBlockingQueue<CacheEntry<K,V>> expiryQueue;
    private final ScheduledExecutorService scheduler;

    TTLHashMap(long  cleanupIntervalMillis){
        this.concurrentHashMap = new ConcurrentHashMap<>();
        this.expiryQueue = new PriorityBlockingQueue<>(
            11,
            Comparator.comparingLong(e-> e.expiryTime)
        );
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::evictExpiredEntries,
                cleanupIntervalMillis,
                cleanupIntervalMillis,
                TimeUnit.MILLISECONDS
        );

    }


    private void evictExpiredEntries(){
        long now = System.currentTimeMillis();
        while (true){
            CacheEntry<K, V> top = this.expiryQueue.peek();
            if (top ==null || top.expiryTime > now)
                break;

            expiryQueue.poll();

            CacheEntry<K, V> current = concurrentHashMap.get(top.key);

            if (current != null && current.expiryTime == top.expiryTime) {
                concurrentHashMap.remove(top.key);
            }
        }
    }

    public void put(K key, V value, long ttlMillis){
        long expiry = System.currentTimeMillis() + ttlMillis;

        CacheEntry<K, V> newEntry = new CacheEntry<>(key, value, expiry);

        CacheEntry<K, V> oldEntry = concurrentHashMap.put(key, newEntry);

        if (oldEntry != null) {
            expiryQueue.remove(oldEntry);
        }

        expiryQueue.put(newEntry);
    }



    public V get(K key){
        CacheEntry<K, V> entry = concurrentHashMap.get(key);
        if(entry == null){
            return null;
        }

        if(isExpired(entry)){
            concurrentHashMap.remove(key, entry);
            return null;
        }
        return entry.value;

    }

    private boolean isExpired(CacheEntry<K,V> entry){
        return System.currentTimeMillis()> entry.expiryTime;
    }

    

}
