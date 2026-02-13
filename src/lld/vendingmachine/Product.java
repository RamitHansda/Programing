package lld.vendingmachine;

/**
 * Represents a product in the vending machine
 */
public class Product {
    private final String id;
    private final String name;
    private final int price; // price in cents
    private final ProductType type;

    public Product(String id, String name, int price, ProductType type) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }

    public ProductType getType() {
        return type;
    }

    @Override
    public String toString() {
        return String.format("%s - %s ($%.2f)", id, name, price / 100.0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Product product = (Product) o;
        return id.equals(product.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
