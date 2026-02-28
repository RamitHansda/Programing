package lld.designpatterns.state;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Context: holds current state and shared data. State transitions update currentState.
 */
public final class VendingMachineContext {

    private VendingMachineState currentState;
    private int balanceCents;
    private final Map<String, Integer> inventory = new HashMap<>();
    private final Map<String, Integer> prices = new HashMap<>();

    public VendingMachineContext() {
        this.currentState = new IdleState();
        this.balanceCents = 0;
    }

    public void setState(VendingMachineState state) {
        this.currentState = Objects.requireNonNull(state);
    }

    public void addProduct(String productId, int quantity, int priceCents) {
        inventory.merge(productId, quantity, Integer::sum);
        prices.put(productId, priceCents);
    }

    public boolean hasProduct(String productId) {
        return inventory.getOrDefault(productId, 0) > 0;
    }

    public int getPriceCents(String productId) {
        return prices.getOrDefault(productId, 0);
    }

    public boolean dispense(String productId) {
        Integer q = inventory.get(productId);
        if (q == null || q <= 0) return false;
        inventory.put(productId, q - 1);
        return true;
    }

    public void insertCoin(int cents) {
        currentState.insertCoin(this, cents);
    }

    public void selectProduct(String productId) {
        currentState.selectProduct(this, productId);
    }

    public void cancel() {
        currentState.cancel(this);
    }

    public int getBalanceCents() { return balanceCents; }
    public void setBalanceCents(int balanceCents) { this.balanceCents = balanceCents; }
    public void addBalance(int cents) { this.balanceCents += cents; }
    public void clearBalance() { this.balanceCents = 0; }
}
