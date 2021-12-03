package designpatterns.decorator;

public class PlainBurger implements Burger {
    @Override
    public int cost() {
        return 10;
    }
}
