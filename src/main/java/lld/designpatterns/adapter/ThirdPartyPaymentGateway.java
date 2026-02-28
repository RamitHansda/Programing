package lld.designpatterns.adapter;

/**
 * Simulated third-party SDK: different request/response shape and method names.
 */
public final class ThirdPartyPaymentGateway {

    public static final class Request {
        public final String merchantRef;
        public final long amountInSmallestUnit;
        public final String currencyCode;
        public final String customerId;

        public Request(String merchantRef, long amountInSmallestUnit, String currencyCode, String customerId) {
            this.merchantRef = merchantRef;
            this.amountInSmallestUnit = amountInSmallestUnit;
            this.currencyCode = currencyCode;
            this.customerId = customerId;
        }
    }

    public static final class Response {
        public final boolean accepted;
        public final String gatewayTransactionId;
        public final String declineReason;

        public Response(boolean accepted, String gatewayTransactionId, String declineReason) {
            this.accepted = accepted;
            this.gatewayTransactionId = gatewayTransactionId;
            this.declineReason = declineReason;
        }
    }

    /**
     * SDK method: different signature and types than our PaymentProcessor.pay().
     */
    public Response initiatePayment(Request req) {
        if (req.amountInSmallestUnit <= 0) {
            return new Response(false, null, "Invalid amount");
        }
        String txnId = "TXN-" + System.currentTimeMillis() + "-" + req.customerId;
        return new Response(true, txnId, null);
    }
}
