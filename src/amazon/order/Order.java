package amazon.order;

import amazon.account.Address;
import amazon.common.Money;
import amazon.payment.PaymentStatus;
import java.time.Instant;
import java.util.List;

/** Immutable outward view. Only OrderService replaces order snapshots. */
public record Order(String id, String buyerId, List<OrderLine> lines, Address address,
                    Money total, Instant createdAt, OrderStatus status,
                    PaymentStatus paymentStatus, Shipment shipment) {
    public Order { lines = List.copyOf(lines); }
    Order withState(OrderStatus next, PaymentStatus payment, Shipment nextShipment) {
        return new Order(id, buyerId, lines, address, total, createdAt, next, payment, nextShipment);
    }
}
