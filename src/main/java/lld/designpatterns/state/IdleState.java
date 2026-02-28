package lld.designpatterns.state;

public final class IdleState implements VendingMachineState {

    @Override
    public void insertCoin(VendingMachineContext ctx, int cents) {
        ctx.addBalance(cents);
        ctx.setState(new HasMoneyState());
    }

    @Override
    public void selectProduct(VendingMachineContext ctx, String productId) {
        // no-op: need to insert money first
    }

    @Override
    public void cancel(VendingMachineContext ctx) {
        // already idle
    }
}
