package lld.designpatterns.facade;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InventoryService {

    private final Map<String, OrderFulfillmentService.Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Integer> reservations = new ConcurrentHashMap<>();

    public void addOrder(OrderFulfillmentService.Order order) {
        orders.put(order.orderId(), order);
    }

    public OrderFulfillmentService.Order getOrder(String orderId) {
        return orders.get(orderId);
    }

    public boolean reserve(String orderId) {
        return reservations.putIfAbsent(orderId, 1) == null;
    }

    public void releaseReservation(String orderId) {
        reservations.remove(orderId);
    }

    public void consumeReservation(String orderId) {
        reservations.remove(orderId);
    }
}
