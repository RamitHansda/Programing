package designpatterns.observer;



public class ObserverDesignPatternDemo {
    @org.junit.jupiter.api.Test
    public void testObserverPattern() {
        Book book = new Book("How to be rich","ramit hansda","hansda publication",1);

        Customer customer1 = new Customer("Gunja", book);
        Customer customer2 = new Customer("Surbhi", book);
        Customer customer3 = new Customer("Harshita",book);

        book.setStock(10);
        customer1.stopNotification(book);
        book.setStock(13);


    }
}
