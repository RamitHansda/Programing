package interview.fampay.lrucache;


import java.util.concurrent.ConcurrentHashMap;

public class LRUCache <K,V> {
    int capacity;
    private ConcurrentHashMap<K,Node<K,V>> mapOfKeyNode;
    private DoublyLinkedList<K,V> doublyLinkedList;

    public LRUCache(int capacity
    ){
        this.doublyLinkedList = new DoublyLinkedList<K,V>();
        this.mapOfKeyNode =  new ConcurrentHashMap<K, Node<K,V>>();
        this.capacity = capacity;
    }

    public synchronized void put(K key, V value){
        if(this.mapOfKeyNode.containsKey(key)){
            Node<K, V> node = mapOfKeyNode.get(key);
            node.value = value;
            doublyLinkedList.removeNode(node);
            doublyLinkedList.addToFirst(node);
        }else{
            if(mapOfKeyNode.size() == this.capacity){
                Node lastNode = doublyLinkedList.removeFromLast();
                if(lastNode != null){
                    mapOfKeyNode.remove(lastNode.key);
                }
            }
            Node newNode = new Node(key, value);
            mapOfKeyNode.put(key, newNode);
            doublyLinkedList.addToFirst(newNode);
        }
    }

    public synchronized V get(K key){
        if(this.mapOfKeyNode.containsKey(key)){
            Node<K,V> node = this.mapOfKeyNode.get(key);
            return node.value;
        }
        return  null;
    }


    public synchronized void remove(K key) {
        if(this.mapOfKeyNode.containsKey(key)){
           Node removeNode = mapOfKeyNode.get(key);
           this.mapOfKeyNode.remove(key);
           this.doublyLinkedList.removeNode(removeNode);
        }
    }


}
