package lld.designpatterns.adapter;

import java.util.Objects;
import java.util.UUID;

/**
 * Adapter: translates between our PaymentProcessor API and the third-party Gateway SDK.
 */
public final class PaymentGatewayAdapter implements PaymentProcessor {

    private final ThirdPartyPaymentGateway gateway;

    public PaymentGatewayAdapter(ThirdPartyPaymentGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway);
    }

    @Override
    public PaymentResult pay(long amountCents, String currency, String userId) {
        if (amountCents <= 0 || currency == null || currency.isBlank() || userId == null || userId.isBlank()) {
            return PaymentResult.fail("Invalid pay arguments");
        }
        ThirdPartyPaymentGateway.Request req = new ThirdPartyPaymentGateway.Request(
                "ref-" + UUID.randomUUID(),
                amountCents,
                currency,
                userId
        );
        ThirdPartyPaymentGateway.Response resp = gateway.initiatePayment(req);
        if (resp.accepted) {
            return PaymentResult.ok(resp.gatewayTransactionId);
        }
        return PaymentResult.fail(resp.declineReason != null ? resp.declineReason : "Payment declined");
    }
}
