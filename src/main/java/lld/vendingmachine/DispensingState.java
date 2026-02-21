package lld.vendingmachine;

/**
 * Dispensing state - product is being dispensed
 */
public class DispensingState implements VendingMachineState {
    
    @Override
    public void insertCoin(VendingMachine machine, Coin coin) {
        System.out.println("Please wait, dispensing product");
    }
    
    @Override
    public void insertNote(VendingMachine machine, Note note) {
        System.out.println("Please wait, dispensing product");
    }
    
    @Override
    public void selectProduct(VendingMachine machine, String productId) {
        System.out.println("Already dispensing a product");
    }
    
    @Override
    public void dispenseProduct(VendingMachine machine) {
        Product product = machine.getSelectedProduct();
        
        if (product == null) {
            System.out.println("No product selected");
            machine.setState(new IdleState());
            return;
        }
        
        // Remove product from inventory
        if (machine.getInventory().removeProduct(product)) {
            System.out.println("Dispensing: " + product.getName());
            
            // Deduct price from balance
            int remainingBalance = machine.getCurrentBalance() - product.getPrice();
            machine.resetBalance();
            
            // Return change if any
            if (remainingBalance > 0) {
                System.out.println("Returning change: $" + remainingBalance / 100.0);
                machine.addMoney(remainingBalance);
                machine.resetBalance();
            }
            
            System.out.println("Thank you for your purchase!");
            machine.setSelectedProduct(null);
            machine.setState(new IdleState());
        } else {
            System.out.println("Error: Product out of stock");
            machine.setState(new HasMoneyState());
        }
    }
    
    @Override
    public int returnChange(VendingMachine machine) {
        System.out.println("Cannot return change while dispensing");
        return 0;
    }
    
    @Override
    public void cancelTransaction(VendingMachine machine) {
        System.out.println("Cannot cancel while dispensing");
    }
    
    @Override
    public String getStateName() {
        return "DISPENSING";
    }
}
