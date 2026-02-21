package lld.vendingmachine;

import java.util.Map;

/**
 * Main VendingMachine class - Context in State Pattern
 * Thread-safe implementation for concurrent access
 */
public class VendingMachine {
    private final Inventory inventory;
    private VendingMachineState currentState;
    private int currentBalance; // in cents
    private Product selectedProduct;
    private final Object lock = new Object();

    public VendingMachine() {
        this.inventory = new Inventory();
        this.currentState = new IdleState();
        this.currentBalance = 0;
        this.selectedProduct = null;
    }

    // Public API methods

    /**
     * Insert a coin into the machine
     * @param coin Coin to insert
     */
    public void insertCoin(Coin coin) {
        synchronized (lock) {
            currentState.insertCoin(this, coin);
        }
    }

    /**
     * Insert a note into the machine
     * @param note Note to insert
     */
    public void insertNote(Note note) {
        synchronized (lock) {
            currentState.insertNote(this, note);
        }
    }

    /**
     * Select a product by ID
     * @param productId Product ID
     */
    public void selectProduct(String productId) {
        synchronized (lock) {
            currentState.selectProduct(this, productId);
        }
    }

    /**
     * Cancel current transaction
     */
    public void cancelTransaction() {
        synchronized (lock) {
            currentState.cancelTransaction(this);
        }
    }

    /**
     * Add product to inventory
     * @param product Product to add
     * @param quantity Quantity to add
     */
    public void addProduct(Product product, int quantity) {
        inventory.addProduct(product, quantity);
    }

    /**
     * Display all available products
     */
    public void displayProducts() {
        System.out.println("\n===== Available Products =====");
        Map<Product, Integer> products = inventory.getAllProducts();
        
        if (products.isEmpty()) {
            System.out.println("No products available");
            return;
        }
        
        for (Map.Entry<Product, Integer> entry : products.entrySet()) {
            Product product = entry.getKey();
            int quantity = entry.getValue();
            String status = quantity > 0 ? "Available (" + quantity + ")" : "Out of Stock";
            System.out.printf("%s | %s\n", product, status);
        }
        System.out.println("==============================\n");
    }

    /**
     * Get current machine status
     */
    public void displayStatus() {
        synchronized (lock) {
            System.out.println("\n===== Vending Machine Status =====");
            System.out.println("State: " + currentState.getStateName());
            System.out.println("Current Balance: $" + currentBalance / 100.0);
            if (selectedProduct != null) {
                System.out.println("Selected Product: " + selectedProduct.getName());
            }
            System.out.println("==================================\n");
        }
    }

    // Package-private methods for state management

    void setState(VendingMachineState state) {
        this.currentState = state;
    }

    VendingMachineState getCurrentState() {
        return currentState;
    }

    void addMoney(int amount) {
        this.currentBalance += amount;
    }

    void resetBalance() {
        this.currentBalance = 0;
    }

    int getCurrentBalance() {
        return currentBalance;
    }

    void setSelectedProduct(Product product) {
        this.selectedProduct = product;
    }

    Product getSelectedProduct() {
        return selectedProduct;
    }

    Inventory getInventory() {
        return inventory;
    }

    // Reset machine to initial state
    public void reset() {
        synchronized (lock) {
            this.currentState = new IdleState();
            this.currentBalance = 0;
            this.selectedProduct = null;
            System.out.println("Vending machine reset");
        }
    }
}
