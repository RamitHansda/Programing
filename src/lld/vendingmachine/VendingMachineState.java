package lld.vendingmachine;

/**
 * Interface representing different states of the vending machine
 * Implements State Design Pattern
 */
public interface VendingMachineState {
    
    /**
     * Insert coin into the machine
     * @param machine Vending machine instance
     * @param coin Coin to insert
     */
    void insertCoin(VendingMachine machine, Coin coin);
    
    /**
     * Insert note into the machine
     * @param machine Vending machine instance
     * @param note Note to insert
     */
    void insertNote(VendingMachine machine, Note note);
    
    /**
     * Select a product
     * @param machine Vending machine instance
     * @param productId Product ID to select
     */
    void selectProduct(VendingMachine machine, String productId);
    
    /**
     * Dispense the selected product
     * @param machine Vending machine instance
     */
    void dispenseProduct(VendingMachine machine);
    
    /**
     * Return change to user
     * @param machine Vending machine instance
     * @return Amount returned in cents
     */
    int returnChange(VendingMachine machine);
    
    /**
     * Cancel transaction and return money
     * @param machine Vending machine instance
     */
    void cancelTransaction(VendingMachine machine);
    
    /**
     * Get the state name
     * @return State name
     */
    String getStateName();
}
