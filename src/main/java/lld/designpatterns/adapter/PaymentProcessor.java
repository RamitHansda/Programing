package lld.designpatterns.adapter;

import java.util.Objects;

/**
 * Our system's payment API. Third-party SDK has a different shape; adapter translates.
 */
public interface PaymentProcessor {

    /**
     * Initiates payment. Returns result with transaction id or error.
     */
    PaymentResult pay(long amountCents, String currency, String userId);

    record PaymentResult(boolean success, String transactionId, String errorMessage) {
        public static PaymentResult ok(String transactionId) {
            return new PaymentResult(true, transactionId, null);
        }
        public static PaymentResult fail(String errorMessage) {
            return new PaymentResult(false, null, errorMessage);
        }
    }
}
