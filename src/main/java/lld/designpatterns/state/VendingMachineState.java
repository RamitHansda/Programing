package lld.designpatterns.state;

/**
 * State: each state encapsulates behavior. Context (VendingMachine) delegates to current state.
 */
public interface VendingMachineState {

    void insertCoin(VendingMachineContext ctx, int cents);
    void selectProduct(VendingMachineContext ctx, String productId);
    void cancel(VendingMachineContext ctx);
}
