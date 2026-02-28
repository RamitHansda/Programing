package lld.designpatterns.state;

public final class HasMoneyState implements VendingMachineState {

    @Override
    public void insertCoin(VendingMachineContext ctx, int cents) {
        ctx.addBalance(cents);
    }

    @Override
    public void selectProduct(VendingMachineContext ctx, String productId) {
        if (!ctx.hasProduct(productId)) {
            return; // or signal "out of stock"
        }
        int price = ctx.getPriceCents(productId);
        if (ctx.getBalanceCents() < price) {
            return; // need more money
        }
        ctx.setBalanceCents(ctx.getBalanceCents() - price);
        ctx.dispense(productId);
        ctx.setState(new DispensingState()); // in a real machine, DispensingState would complete then set Idle
    }

    @Override
    public void cancel(VendingMachineContext ctx) {
        ctx.clearBalance();
        ctx.setState(new IdleState());
    }
}
