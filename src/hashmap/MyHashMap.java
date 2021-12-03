package hashmap;


public class MyHashMap<K,V> {
    private Entry<K,V>[] bucket;
    private static final int INITIAL_CAPACITY =16;
    private int size=0;

    public int getSize() {
        return size;
    }

    public MyHashMap() {
        this(INITIAL_CAPACITY);
    }

    public MyHashMap(int capacity) {
        this.bucket = new Entry[capacity];
    }



    public int getBucketSize(){
        return bucket.length;
    }

    public void put(K key, V value){
        Entry<K,V> entry = new Entry<>(key,value,null);
        int index = getBucketIndex(key);
        Entry<K,V> existing = bucket[index];
        if(existing == null){
            bucket[index] = entry;
            size++;
        }else{
            while (existing.getNext()!=null){
                if(existing.getKey().equals(entry.getKey())){
                    existing.setValue(entry.getValue());
                    return;
                }
                existing = existing.getNext();
            }
            if(existing.getKey().equals(entry.getKey())){
                existing.setValue(entry.getValue());
            }
            else{
                existing.setNext(entry);
                size++;
            }
        }
    }

    public V get(K key){
        Entry<K,V> foundEntry = bucket[getBucketIndex(key)];
        if(foundEntry != null){
            return foundEntry.getValue();
        }
        return null;
    }

    private int getBucketIndex(K key){
        return key.hashCode()%getBucketSize();
    }

}


