package lld.lrucache;

public class DoublyLinkedList<K,V> {
    Node<K,V> head;
    Node<K,V> tail;

    DoublyLinkedList(){
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public void remove(Node<K,V> node){
        node.prev.next = node.next.prev;
        node.prev = node.next.prev;
    }

    public Node<K,V> removeLast(){
        if (tail.prev == head) return null;
        Node<K, V> last = tail.prev;
        remove(last);
        return last;
    }

    public void addFirst(Node<K,V> node){
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;

    }

    public void moveToFront(Node<K,V> node){
        remove(node);
        this.addFirst(node);
    }

}
