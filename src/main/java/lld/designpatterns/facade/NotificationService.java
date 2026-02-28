package lld.designpatterns.facade;

public interface NotificationService {

    void sendOrderConfirmation(String userId, String orderId, String trackingId);
}
