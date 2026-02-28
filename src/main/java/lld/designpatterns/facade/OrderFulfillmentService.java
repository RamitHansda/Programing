package lld.designpatterns.facade;

import java.util.Objects;

/**
 * Facade: single entry point for order fulfillment. Hides inventory, payment, shipping, notifications.
 */
public final class OrderFulfillmentService {

    private final InventoryService inventory;
    private final PaymentService payment;
    private final ShippingService shipping;
    private final NotificationService notification;

    public OrderFulfillmentService(InventoryService inventory, PaymentService payment,
                                   ShippingService shipping, NotificationService notification) {
        this.inventory = Objects.requireNonNull(inventory);
        this.payment = Objects.requireNonNull(payment);
        this.shipping = Objects.requireNonNull(shipping);
        this.notification = Objects.requireNonNull(notification);
    }

    /**
     * Coordinates the full flow. Callers don't need to know about subsystems.
     */
    public FulfillmentResult fulfillOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return FulfillmentResult.fail(orderId, "Invalid orderId");
        }
        Order order = inventory.getOrder(orderId);
        if (order == null) {
            return FulfillmentResult.fail(orderId, "Order not found");
        }
        if (!inventory.reserve(orderId)) {
            return FulfillmentResult.fail(orderId, "Insufficient inventory");
        }
        PaymentService.PaymentResult payResult = payment.charge(order.userId(), order.totalCents(), order.currency());
        if (!payResult.success()) {
            inventory.releaseReservation(orderId);
            return FulfillmentResult.fail(orderId, "Payment failed: " + payResult.errorMessage());
        }
        String trackingId = shipping.ship(orderId, order.shippingAddress());
        inventory.consumeReservation(orderId);
        notification.sendOrderConfirmation(order.userId(), orderId, trackingId);
        return FulfillmentResult.ok(orderId, trackingId);
    }

    public record Order(String orderId, String userId, long totalCents, String currency, String shippingAddress) {}

    public record FulfillmentResult(boolean success, String orderId, String trackingId, String errorMessage) {
        static FulfillmentResult ok(String orderId, String trackingId) {
            return new FulfillmentResult(true, orderId, trackingId, null);
        }
        static FulfillmentResult fail(String orderId, String errorMessage) {
            return new FulfillmentResult(false, orderId, null, errorMessage);
        }
    }
}
