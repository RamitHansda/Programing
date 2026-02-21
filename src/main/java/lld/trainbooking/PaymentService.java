package lld.trainbooking;

import java.util.UUID;

/**
 * Service to handle payment processing
 * In a real system, this would integrate with payment gateways
 */
public class PaymentService {
    
    public PaymentResult processPayment(String userId, double amount, PaymentMethod method) {
        // Simulate payment processing
        String transactionId = generateTransactionId();
        
        // Simulate random payment success/failure (90% success rate)
        boolean success = Math.random() < 0.9;
        
        if (success) {
            System.out.println("Payment successful: ₹" + amount + " via " + method);
            return new PaymentResult(true, transactionId, "Payment successful");
        } else {
            System.out.println("Payment failed for amount: ₹" + amount);
            return new PaymentResult(false, null, "Payment failed");
        }
    }

    private String generateTransactionId() {
        return "TXN" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }

    public enum PaymentMethod {
        CREDIT_CARD,
        DEBIT_CARD,
        UPI,
        NET_BANKING,
        WALLET
    }

    public static class PaymentResult {
        private final boolean success;
        private final String transactionId;
        private final String message;

        public PaymentResult(boolean success, String transactionId, String message) {
            this.success = success;
            this.transactionId = transactionId;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public String getMessage() {
            return message;
        }
    }
}
