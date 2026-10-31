import amazon.account.*;
import amazon.cart.ShoppingCart;
import amazon.catalog.*;
import amazon.common.Money;
import amazon.notification.NotificationService;
import amazon.order.*;
import amazon.payment.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static amazon.account.Account.Role.*;

/** Focused concurrency/failure checks; no test framework required. */
public class OrderServiceChecks {
    public static void main(String[] args) throws Exception {
        Account admin = new Account("admin", "Admin", Set.of(ADMIN));
        Account seller = new Account("seller", "Seller", Set.of(SELLER));
        Account buyer = new Account("buyer", "Buyer", Set.of(BUYER));
        Address address = new Address("Street", "City", "00123", "Country");
        Catalog catalog = new Catalog();
        catalog.saveCategory(admin, new Category("category", "Category"));
        catalog.addProduct(seller, new Product("p", seller.id(), "Product", "category", Money.of("10")), 2);
        OrderService service = new OrderService(catalog, new NotificationService(List.of()), Clock.systemUTC());
        ShoppingCart cart = new ShoppingCart(buyer);
        cart.add("p", 1);
        AtomicInteger charges = new AtomicInteger();
        AtomicInteger refunds = new AtomicInteger();
        PaymentMethod payment = new PaymentMethod() {
            public PaymentStatus pay(Money total) { charges.incrementAndGet(); return PaymentStatus.PAID; }
            public void refund(Money total) { refunds.incrementAndGet(); }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Order> request = () -> {
                start.await();
                return service.checkout(buyer, cart, address, payment, "same-request");
            };
            Future<Order> first = pool.submit(request);
            Future<Order> second = pool.submit(request);
            start.countDown();
            Order order = first.get(5, TimeUnit.SECONDS);
            check(order.id().equals(second.get(5, TimeUnit.SECONDS).id()), "Duplicate orders");
            check(charges.get() == 1 && catalog.available("p") == 1, "Duplicate charge/stock decrement");

            CountDownLatch race = new CountDownLatch(1);
            AtomicInteger successfulTransitions = new AtomicInteger();
            Future<?> cancel = pool.submit(() -> {
                race.await();
                try { service.cancel(buyer, order.id()); successfulTransitions.incrementAndGet(); }
                catch (IllegalStateException expected) { /* Shipping won. */ }
                return null;
            });
            Future<?> ship = pool.submit(() -> {
                race.await();
                try {
                    service.ship(admin, order.id(), "tracking", Duration.ofDays(1));
                    successfulTransitions.incrementAndGet();
                } catch (IllegalStateException expected) { /* Cancellation won. */ }
                return null;
            });
            race.countDown();
            cancel.get(5, TimeUnit.SECONDS);
            ship.get(5, TimeUnit.SECONDS);
            Order finalOrder = service.get(buyer, order.id());
            check(successfulTransitions.get() == 1, "Shipping and cancellation both succeeded");
            boolean cancelled = finalOrder.status() == OrderStatus.CANCELLED;
            check(refunds.get() == (cancelled ? 1 : 0), "Incorrect refund count");
            check(catalog.available("p") == (cancelled ? 2 : 1), "Incorrect stock after race");

            ShoppingCart failingCart = new ShoppingCart(buyer);
            failingCart.add("p", 1);
            int before = catalog.available("p");
            PaymentMethod failingPayment = new PaymentMethod() {
                public PaymentStatus pay(Money total) { throw new IllegalStateException("Before charge"); }
                public void refund(Money total) { throw new AssertionError("Nothing to refund"); }
            };
            try {
                service.checkout(buyer, failingCart, address, failingPayment, "failed");
                throw new AssertionError("Expected payment failure");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().equals("Before charge"), "Unexpected failure");
            }
            check(catalog.available("p") == before && failingCart.snapshot().get("p") == 1,
                    "Exception lost stock or cart");

            Account anotherSeller = new Account("seller2", "Other seller", Set.of(SELLER));
            try {
                catalog.removeProduct(anotherSeller, "p");
                throw new AssertionError("Another seller removed the listing");
            } catch (SecurityException expected) { /* Ownership enforced beyond role membership. */ }

            Order cash = service.checkout(buyer, failingCart, address, new CashOnDelivery(), "cash");
            Order cancelledCash = service.cancel(buyer, cash.id());
            check(cancelledCash.paymentStatus() == PaymentStatus.CANCELLED, "Uncollected cash marked refunded");
            System.out.println("ORDER SERVICE CHECKS PASSED");
        } finally { pool.shutdownNow(); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
