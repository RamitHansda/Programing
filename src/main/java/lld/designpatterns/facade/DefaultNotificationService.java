package lld.designpatterns.facade;

public final class DefaultNotificationService implements NotificationService {

    @Override
    public void sendOrderConfirmation(String userId, String orderId, String trackingId) {
        // Email/push in production
    }
}
