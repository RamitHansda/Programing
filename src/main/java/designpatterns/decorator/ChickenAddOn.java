package designpatterns.decorator;

public class ChickenAddOn implements BurgerAddOnDecorator{
    Burger burger;
    @Override
    public int cost() {
        return this.burger.cost() + 25;
    }

    public ChickenAddOn(Burger burger) {
        this.burger = burger;
    }
}
