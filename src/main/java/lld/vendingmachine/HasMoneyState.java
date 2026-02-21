package lld.vendingmachine;

/**
 * HasMoney state - money has been inserted, waiting for product selection
 */
public class HasMoneyState implements VendingMachineState {
    
    @Override
    public void insertCoin(VendingMachine machine, Coin coin) {
        System.out.println("Coin inserted: " + coin + " ($" + coin.getValue() / 100.0 + ")");
        machine.addMoney(coin.getValue());
        System.out.println("Current balance: $" + machine.getCurrentBalance() / 100.0);
    }
    
    @Override
    public void insertNote(VendingMachine machine, Note note) {
        System.out.println("Note inserted: " + note + " ($" + note.getValue() / 100.0 + ")");
        machine.addMoney(note.getValue());
        System.out.println("Current balance: $" + machine.getCurrentBalance() / 100.0);
    }
    
    @Override
    public void selectProduct(VendingMachine machine, String productId) {
        Product product = machine.getInventory().getProduct(productId);
        
        if (product == null) {
            System.out.println("Invalid product selection");
            return;
        }
        
        if (!machine.getInventory().isAvailable(product)) {
            System.out.println("Product out of stock: " + product.getName());
            return;
        }
        
        if (machine.getCurrentBalance() < product.getPrice()) {
            int remaining = product.getPrice() - machine.getCurrentBalance();
            System.out.println("Insufficient funds. Please insert $" + remaining / 100.0 + " more");
            return;
        }
        
        machine.setSelectedProduct(product);
        System.out.println("Product selected: " + product);
        machine.setState(new DispensingState());
        machine.getCurrentState().dispenseProduct(machine);
    }
    
    @Override
    public void dispenseProduct(VendingMachine machine) {
        System.out.println("Please select a product first");
    }
    
    @Override
    public int returnChange(VendingMachine machine) {
        int change = machine.getCurrentBalance();
        System.out.println("Returning change: $" + change / 100.0);
        machine.resetBalance();
        machine.setState(new IdleState());
        return change;
    }
    
    @Override
    public void cancelTransaction(VendingMachine machine) {
        System.out.println("Transaction cancelled");
        returnChange(machine);
    }
    
    @Override
    public String getStateName() {
        return "HAS_MONEY";
    }
}
