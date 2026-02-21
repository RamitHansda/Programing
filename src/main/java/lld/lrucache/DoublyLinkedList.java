package lld.lrucache;

public class DoublyLinkedList<K,V> {
    private Node<K,V> head;
    private Node<K,V> tail;

    DoublyLinkedList(){
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        this.head.next = tail;
        this.tail.prev = head;
    }

    public void remove(Node<K, V> node){
        if(node != head || node != tail){
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }
    }

    public void addFirst(Node<K,V> node){
        node.next =  head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    public void moveToFront(Node<K,V> node){
        this.remove(node);
        this.addFirst(node);
    }

    public Node<K,V> removeLast(){
        if(tail.prev != head);
        Node lastNode = tail.prev;
        remove(lastNode);
        return lastNode;
    }

}
