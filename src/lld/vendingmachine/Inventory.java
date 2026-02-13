package lld.vendingmachine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages inventory of products in the vending machine
 * Thread-safe implementation using ConcurrentHashMap
 */
public class Inventory {
    private final Map<Product, Integer> productQuantity;
    private final Map<String, Product> productById;

    public Inventory() {
        this.productQuantity = new ConcurrentHashMap<>();
        this.productById = new ConcurrentHashMap<>();
    }

    /**
     * Add product to inventory
     * @param product Product to add
     * @param quantity Quantity to add
     */
    public void addProduct(Product product, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        productQuantity.merge(product, quantity, Integer::sum);
        productById.put(product.getId(), product);
    }

    /**
     * Remove one unit of product from inventory
     * @param product Product to remove
     * @return true if successfully removed, false if out of stock
     */
    public synchronized boolean removeProduct(Product product) {
        Integer quantity = productQuantity.get(product);
        if (quantity == null || quantity <= 0) {
            return false;
        }
        
        if (quantity == 1) {
            productQuantity.remove(product);
        } else {
            productQuantity.put(product, quantity - 1);
        }
        return true;
    }

    /**
     * Check if product is available
     * @param product Product to check
     * @return true if available, false otherwise
     */
    public boolean isAvailable(Product product) {
        Integer quantity = productQuantity.get(product);
        return quantity != null && quantity > 0;
    }

    /**
     * Get product by ID
     * @param productId Product ID
     * @return Product if found, null otherwise
     */
    public Product getProduct(String productId) {
        return productById.get(productId);
    }

    /**
     * Get quantity of a product
     * @param product Product to check
     * @return Quantity available
     */
    public int getQuantity(Product product) {
        return productQuantity.getOrDefault(product, 0);
    }

    /**
     * Get all products with their quantities
     * @return Map of products and quantities
     */
    public Map<Product, Integer> getAllProducts() {
        return new HashMap<>(productQuantity);
    }

    /**
     * Check if inventory is empty
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return productQuantity.isEmpty() || 
               productQuantity.values().stream().allMatch(q -> q <= 0);
    }

    /**
     * Clear all inventory
     */
    public void clear() {
        productQuantity.clear();
        productById.clear();
    }
}
