package lld.designpatterns.facade;

import java.util.UUID;

public final class DefaultShippingService implements ShippingService {

    @Override
    public String ship(String orderId, String address) {
        return "TRK-" + UUID.randomUUID();
    }
}
