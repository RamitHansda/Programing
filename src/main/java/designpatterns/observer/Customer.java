package designpatterns.observer;

public class Customer implements Observer{
    String name;

    public String getName() {
        return name;
    }

    public Customer(String name,SubjectLibrary subjectLibrary) {
        this.name = name;
        subjectLibrary.subscribeObserver(this);
    }

    @Override
    public void update(int stock, String bookName) {
        System.out.println("Hello "+this.name+ " the book named "+ bookName + " has only "+ stock+ " in stock");
    }

    public void stopNotification(SubjectLibrary subjectLibrary){
        subjectLibrary.unsubscribeObserver(this);
    }
}
