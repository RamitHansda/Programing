package lld.designpatterns.facade;

public interface PaymentService {

    record PaymentResult(boolean success, String transactionId, String errorMessage) {}

    PaymentResult charge(String userId, long amountCents, String currency);
}
