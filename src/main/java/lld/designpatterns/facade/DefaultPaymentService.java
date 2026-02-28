package lld.designpatterns.facade;

import java.util.UUID;

public final class DefaultPaymentService implements PaymentService {

    @Override
    public PaymentResult charge(String userId, long amountCents, String currency) {
        if (amountCents <= 0) {
            return new PaymentResult(false, null, "Invalid amount");
        }
        return new PaymentResult(true, "txn-" + UUID.randomUUID(), null);
    }
}
