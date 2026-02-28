package lld.designpatterns.state;

public final class DispensingState implements VendingMachineState {

    @Override
    public void insertCoin(VendingMachineContext ctx, int cents) {
        // ignore during dispensing
    }

    @Override
    public void selectProduct(VendingMachineContext ctx, String productId) {
        // already dispensing
    }

    @Override
    public void cancel(VendingMachineContext ctx) {
        ctx.setState(ctx.getBalanceCents() > 0 ? new HasMoneyState() : new IdleState());
    }
}
