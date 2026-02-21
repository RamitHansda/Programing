package designpatterns.observer;

import java.util.ArrayList;
import java.util.List;

public class Book implements SubjectLibrary{
    String title;
    String author;
    String publication;
    int stock;
    List<Observer> subscriberList = new ArrayList<>();

    public String getTitle() {
        return title;
    }

    public Book(String title, String author, String publication, int stock) {
        this.title = title;
        this.author = author;
        this.publication = publication;
        this.stock = stock;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getPublication() {
        return publication;
    }

    public void setPublication(String publication) {
        this.publication = publication;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
        notifyObserver();
    }

    public List<Observer> getSubscriberList() {
        return subscriberList;
    }

    public void setSubscriberList(List<Observer> subscriberList) {
        this.subscriberList = subscriberList;
    }

    @Override
    public void subscribeObserver(Observer ob) {
        subscriberList.add(ob);
    }

    @Override
    public void unsubscribeObserver(Observer ob) {
        subscriberList.remove(ob);
    }

    @Override
    public void notifyObserver() {
        for (Observer ob:subscriberList) {
            ob.update(getStock(),getTitle());
        }
    }
}
