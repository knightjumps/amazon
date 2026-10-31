package amazon.order;

import amazon.account.Account;
import amazon.account.Address;
import amazon.cart.ShoppingCart;
import amazon.catalog.Catalog;
import amazon.catalog.Product;
import amazon.common.Money;
import amazon.notification.NotificationService;
import amazon.payment.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import static amazon.account.Account.Role.*;

/** In-memory application service and order store, intentionally one concurrency boundary. */
public final class OrderService {
    private record CheckoutKey(String buyerId, String key) {}
    private record CheckoutResult(ShoppingCart cart, Address address, PaymentMethod payment, String orderId) {}
    private final Catalog catalog;
    private final NotificationService notifications;
    private final Clock clock;
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Map<String, PaymentMethod> paymentMethods = new HashMap<>();
    private final Map<CheckoutKey, CheckoutResult> completed = new HashMap<>();

    public OrderService(Catalog catalog, NotificationService notifications, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog);
        this.notifications = Objects.requireNonNull(notifications);
        this.clock = Objects.requireNonNull(clock);
    }
    public synchronized Order checkout(Account buyer, ShoppingCart cart, Address address,
                                       PaymentMethod payment, String requestKey) {
        buyer.require(BUYER);
        Objects.requireNonNull(cart);
        Objects.requireNonNull(address);
        Objects.requireNonNull(payment);
        if (!buyer.id().equals(cart.ownerId())) throw new SecurityException("Not cart owner");
        if (requestKey == null || requestKey.isBlank()) throw new IllegalArgumentException("Missing request key");
        CheckoutKey key = new CheckoutKey(buyer.id(), requestKey);
        CheckoutResult previous = completed.get(key);
        if (previous != null) {
            if (previous.cart() != cart || !previous.address().equals(address) || previous.payment() != payment)
                throw new IllegalArgumentException("Request key reused with different parameters");
            return orders.get(previous.orderId());
        }
        // Lock order is always service -> cart -> catalog. Simulated payments are immediate.
        synchronized (cart) {
            synchronized (catalog) {
                Map<String, Integer> quantities = cart.snapshot();
                if (quantities.isEmpty()) throw new IllegalStateException("Cart is empty");
                List<OrderLine> lines = new ArrayList<>();
                Money total = Money.ZERO;
                for (var entry : quantities.entrySet()) {
                    Product product = catalog.get(entry.getKey());
                    OrderLine line = new OrderLine(product.id(), product.sellerId(), product.name(),
                            product.price(), entry.getValue());
                    lines.add(line);
                    total = total.add(line.subtotal());
                }
                catalog.reserve(quantities);
                PaymentStatus outcome;
                try {
                    outcome = Objects.requireNonNull(payment.pay(total));
                    if (outcome != PaymentStatus.PAID && outcome != PaymentStatus.AWAITING_COLLECTION)
                        throw new IllegalStateException("Payment declined");
                } catch (RuntimeException failure) {
                    catalog.release(quantities);
                    throw failure;
                }
                Order order = new Order(UUID.randomUUID().toString(), buyer.id(), lines, address, total,
                        clock.instant(), OrderStatus.CONFIRMED, outcome, null);
                orders.put(order.id(), order);
                paymentMethods.put(order.id(), payment);
                completed.put(key, new CheckoutResult(cart, address, payment, order.id()));
                // Owner could be blocked concurrently after validation; completion must still be recorded.
                // Cart mutation is performed before leaving its lock.
                cart.clear();
                notifications.publish(buyer.id(), "Order confirmed: " + order.id() + " (" + total + ")");
                return order;
            }
        }
    }
    public synchronized Order cancel(Account buyer, String orderId) {
        Order order = get(buyer, orderId);
        if (order.status() == OrderStatus.CANCELLED) return order;
        if (order.status() != OrderStatus.CONFIRMED)
            throw new IllegalStateException("Only unshipped orders can be cancelled");
        PaymentStatus payment = order.paymentStatus();
        if (payment == PaymentStatus.PAID) {
            paymentMethods.get(orderId).refund(order.total());
            payment = PaymentStatus.REFUNDED;
        } else {
            payment = PaymentStatus.CANCELLED; // No cash was collected, so no refund is due.
        }
        Map<String, Integer> quantities = new HashMap<>();
        order.lines().forEach(line -> quantities.put(line.productId(), line.quantity()));
        catalog.release(quantities);
        Order cancelled = order.withState(OrderStatus.CANCELLED, payment, null);
        orders.put(orderId, cancelled);
        notifications.publish(buyer.id(), "Order cancelled: " + orderId);
        return cancelled;
    }
    public synchronized Order ship(Account admin, String orderId, String trackingId, Duration transitTime) {
        admin.require(ADMIN); // Admin stands in for a trusted fulfillment worker.
        if (trackingId == null || trackingId.isBlank() || transitTime == null || transitTime.isNegative())
            throw new IllegalArgumentException("Invalid shipment");
        Order order = find(orderId);
        if (order.status() != OrderStatus.CONFIRMED) throw new IllegalStateException("Order cannot ship");
        Shipment shipment = new Shipment(trackingId, clock.instant().plus(transitTime), Shipment.Status.IN_TRANSIT);
        Order shipped = order.withState(OrderStatus.SHIPPED, order.paymentStatus(), shipment);
        orders.put(orderId, shipped);
        notifications.publish(order.buyerId(), "Shipment in transit: " + trackingId);
        return shipped;
    }
    public synchronized Order deliver(Account admin, String orderId, boolean cashCollected) {
        admin.require(ADMIN);
        Order order = find(orderId);
        if (order.status() != OrderStatus.SHIPPED) throw new IllegalStateException("Order is not in transit");
        if (order.paymentStatus() == PaymentStatus.AWAITING_COLLECTION && !cashCollected)
            throw new IllegalStateException("Collect cash before completing delivery");
        Shipment old = order.shipment();
        Shipment shipment = new Shipment(old.trackingId(), old.estimatedArrival(), Shipment.Status.DELIVERED);
        Order delivered = order.withState(OrderStatus.DELIVERED, PaymentStatus.PAID, shipment);
        orders.put(orderId, delivered);
        notifications.publish(order.buyerId(), "Shipment delivered: " + shipment.trackingId());
        return delivered;
    }
    public synchronized Order get(Account buyer, String orderId) {
        buyer.require(BUYER);
        Order order = find(orderId);
        if (!order.buyerId().equals(buyer.id())) throw new SecurityException("Not order owner");
        return order;
    }
    public synchronized List<Order> history(Account buyer) {
        buyer.require(BUYER);
        return orders.values().stream().filter(o -> o.buyerId().equals(buyer.id())).toList();
    }
    private Order find(String id) {
        Order order = orders.get(id);
        if (order == null) throw new IllegalArgumentException("Unknown order: " + id);
        return order;
    }
}
