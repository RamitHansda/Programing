package lld.vendingmachine;

/**
 * Demo class to showcase VendingMachine functionality
 */
public class VendingMachineDemo {
    
    public static void main(String[] args) {
        System.out.println("========== Vending Machine Demo ==========\n");
        
        // Create vending machine
        VendingMachine machine = new VendingMachine();
        
        // Add products to inventory
        setupInventory(machine);
        
        // Display available products
        machine.displayProducts();
        
        // Scenario 1: Successful purchase with exact money
        System.out.println("\n--- Scenario 1: Successful purchase with exact money ---");
        scenario1(machine);
        
        // Scenario 2: Purchase with change
        System.out.println("\n--- Scenario 2: Purchase with change ---");
        scenario2(machine);
        
        // Scenario 3: Insufficient funds
        System.out.println("\n--- Scenario 3: Insufficient funds ---");
        scenario3(machine);
        
        // Scenario 4: Product out of stock
        System.out.println("\n--- Scenario 4: Product out of stock ---");
        scenario4(machine);
        
        // Scenario 5: Cancel transaction
        System.out.println("\n--- Scenario 5: Cancel transaction ---");
        scenario5(machine);
        
        // Scenario 6: Invalid product selection
        System.out.println("\n--- Scenario 6: Invalid product selection ---");
        scenario6(machine);
        
        // Display final inventory
        System.out.println("\n--- Final Inventory ---");
        machine.displayProducts();
    }
    
    private static void setupInventory(VendingMachine machine) {
        machine.addProduct(new Product("A1", "Coke", 125, ProductType.BEVERAGE), 10);
        machine.addProduct(new Product("A2", "Pepsi", 125, ProductType.BEVERAGE), 8);
        machine.addProduct(new Product("B1", "Lays Chips", 150, ProductType.CHIPS), 5);
        machine.addProduct(new Product("B2", "Doritos", 175, ProductType.CHIPS), 1); // Only 1 in stock
        machine.addProduct(new Product("C1", "Snickers", 100, ProductType.CHOCOLATE), 15);
        machine.addProduct(new Product("C2", "KitKat", 100, ProductType.CHOCOLATE), 12);
        
        // Add M&Ms with 1 quantity, then remove it to simulate out of stock
        Product mms = new Product("D1", "M&Ms", 125, ProductType.CANDY);
        machine.addProduct(mms, 1);
        machine.getInventory().removeProduct(mms); // Now out of stock
    }
    
    // Scenario 1: Buy Coke with exact money
    private static void scenario1(VendingMachine machine) {
        machine.insertNote(Note.ONE);  // $1.00
        machine.insertCoin(Coin.QUARTER); // $0.25
        machine.selectProduct("A1");    // Coke $1.25
    }
    
    // Scenario 2: Buy Lays with $2, get change
    private static void scenario2(VendingMachine machine) {
        machine.insertNote(Note.ONE);   // $1.00
        machine.insertNote(Note.ONE);   // $1.00 (total $2.00)
        machine.selectProduct("B1");    // Lays $1.50 (change $0.50)
    }
    
    // Scenario 3: Try to buy Doritos with insufficient money
    private static void scenario3(VendingMachine machine) {
        machine.insertNote(Note.ONE);   // $1.00
        machine.selectProduct("B2");    // Doritos $1.75 (need $0.75 more)
        machine.insertCoin(Coin.QUARTER); // $0.25
        machine.insertCoin(Coin.QUARTER); // $0.25
        machine.insertCoin(Coin.QUARTER); // $0.25 (total $1.75)
        machine.selectProduct("B2");    // Now should work
    }
    
    // Scenario 4: Try to buy out of stock M&Ms
    private static void scenario4(VendingMachine machine) {
        machine.insertNote(Note.ONE);   // $1.00
        machine.insertCoin(Coin.QUARTER); // $0.25
        machine.selectProduct("D1");    // M&Ms (out of stock)
        machine.cancelTransaction();    // Get money back
    }
    
    // Scenario 5: Insert money and cancel
    private static void scenario5(VendingMachine machine) {
        machine.insertNote(Note.FIVE);  // $5.00
        machine.displayStatus();
        machine.cancelTransaction();    // Cancel and get money back
    }
    
    // Scenario 6: Try to select invalid product
    private static void scenario6(VendingMachine machine) {
        machine.insertNote(Note.ONE);   // $1.00
        machine.selectProduct("Z9");    // Invalid product
        machine.cancelTransaction();    // Get money back
    }
}
