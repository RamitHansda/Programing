package lld.vendingmachine;

/**
 * Idle state - waiting for user interaction
 */
public class IdleState implements VendingMachineState {
    
    @Override
    public void insertCoin(VendingMachine machine, Coin coin) {
        System.out.println("Coin inserted: " + coin + " ($" + coin.getValue() / 100.0 + ")");
        machine.addMoney(coin.getValue());
        machine.setState(new HasMoneyState());
    }
    
    @Override
    public void insertNote(VendingMachine machine, Note note) {
        System.out.println("Note inserted: " + note + " ($" + note.getValue() / 100.0 + ")");
        machine.addMoney(note.getValue());
        machine.setState(new HasMoneyState());
    }
    
    @Override
    public void selectProduct(VendingMachine machine, String productId) {
        System.out.println("Please insert money first");
    }
    
    @Override
    public void dispenseProduct(VendingMachine machine) {
        System.out.println("Please select a product and insert money");
    }
    
    @Override
    public int returnChange(VendingMachine machine) {
        System.out.println("No money to return");
        return 0;
    }
    
    @Override
    public void cancelTransaction(VendingMachine machine) {
        System.out.println("No transaction to cancel");
    }
    
    @Override
    public String getStateName() {
        return "IDLE";
    }
}
