package interview.fampay.lrucache;

public class DoublyLinkedList<K,V> {
    private Node<K,V> head;
    private Node<K,V> tail;

    DoublyLinkedList(){
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        this.head.next = tail;
        this.tail.prev = head;
    }
// head-next -> node.next-> tail
   // tail.prev ->  head;

    /**
     *
     * Head-next->node-next>tail
     * Head<-prev-tail
     * when add a new node
     * new node.next points to head.next
     */
    public void addToFirst(Node<K,V> node){
        node.next =  head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    public void removeNode(Node<K, V> node){
        if(node != head || node != tail){
            node.prev.next = node.next;
            node.next.prev = node.prev;
        }
    }

    public void moveToFront(Node<K,V> node){
        this.removeNode(node);
        this.addToFirst(node);
    }

    public Node<K,V> removeFromLast(){
        if(tail.prev != head);
        Node lastNode = tail.prev;
        removeNode(lastNode);
        return lastNode;
    }

}
