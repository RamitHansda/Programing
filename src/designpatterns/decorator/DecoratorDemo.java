package designpatterns.decorator;

public class DecoratorDemo {
    public static void main(String[] args) {
        Burger myBurger = new PlainBurger();
        myBurger = new ChickenAddOn(myBurger);
        System.out.println("Chicken burger price "+myBurger.cost());
        myBurger = new ChessAddOn(myBurger);
        System.out.println("Chicken Chess burger price "+myBurger.cost());
    }
}
