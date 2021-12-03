package designpatterns.decorator;

public class ChessAddOn implements BurgerAddOnDecorator{
    Burger burger;
    @Override
    public int cost() {
        return this.burger.cost() + 15;
    }

    public ChessAddOn(Burger burger) {
        this.burger = burger;
    }
}
