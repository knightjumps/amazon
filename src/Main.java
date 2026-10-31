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

/**
 * Run this class. Checks throw AssertionError even when JVM assertions are disabled.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        Catalog catalog = new Catalog();
        NotificationService notifications = new NotificationService(List.of(
                (id, text) -> System.out.println("  EMAIL [" + id + "] " + text),
                (id, text) -> System.out.println("  SMS   [" + id + "] " + text)));
        OrderService orders = new OrderService(catalog, notifications, Clock.systemUTC());
        Account admin = account("admin", ADMIN);
        Account seller = account("seller", SELLER);
        Account buyer = account("buyer", BUYER);
        Account otherBuyer = account("other-buyer", BUYER);
        Address address = new Address("12 Lake Road", "Bengaluru", "560001", "India");

        section("1. Admin categories; seller listings; guest search");
        catalog.saveCategory(admin, new Category("electronics", "Electronics"));
        catalog.saveCategory(admin, new Category("books", "Books"));
        catalog.saveCategory(admin, new Category("unused", "Temporary category"));
        catalog.saveCategory(admin, new Category("unused", "Renamed category"));
        catalog.deleteCategory(admin, "unused");
        catalog.addProduct(seller, new Product("keyboard", seller.id(), "Mechanical Keyboard",
                "electronics", Money.of("2000")), 5);
        catalog.addProduct(seller, new Product("book", seller.id(), "Java Design Book",
                "books", Money.of("500")), 10);
        check(catalog.searchByName("KEYBOARD").size() == 1, "Guest search ignores case");
        check(catalog.searchByCategory("books").size() == 1, "Category search");
        expect(SecurityException.class, () -> catalog.changePrice(otherBuyer, "book", Money.of("1")));
        expect(IllegalStateException.class, () -> catalog.deleteCategory(admin, "books"));

        section("2. Cart add/update/remove; card checkout; duplicate request");
        ShoppingCart cart = new ShoppingCart(buyer);
        cart.add("keyboard", 1);
        cart.add("book", 1);
        cart.add("book", 2);
        cart.changeQuantity("book", 2);
        cart.remove("keyboard");
        cart.add("keyboard", 1);
        expect(IllegalArgumentException.class, () -> cart.add("book", 0));
        expect(SecurityException.class, () -> orders.checkout(otherBuyer, cart, address,
                new CardPayment(true), "stolen-cart"));
        PaymentMethod card = new CardPayment(true);
        Order paid = orders.checkout(buyer, cart, address, card, "checkout-1");
        check(paid.total().equals(Money.of("3000")), "Correct total with quantities");
        check(cart.snapshot().isEmpty(), "Successful checkout empties cart");
        check(catalog.available("book") == 8 && catalog.available("keyboard") == 4, "Stock reduced");
        check(orders.checkout(buyer, cart, address, card, "checkout-1").id().equals(paid.id()),
                "Retry returns same order");
        check(catalog.available("book") == 8, "Retry does not consume stock again");
        expect(IllegalArgumentException.class, () -> orders.checkout(buyer, cart, address,
                new CardPayment(true), "checkout-1"));
        catalog.changePrice(seller, "book", Money.of("600"));
        check(paid.total().equals(Money.of("3000")), "Historical price stays unchanged");
        expect(SecurityException.class, () -> orders.get(otherBuyer, paid.id()));

        section("3. Pre-shipment cancellation/refund; repeated cancellation");
        Order cancelled = orders.cancel(buyer, paid.id());
        check(cancelled.paymentStatus() == PaymentStatus.REFUNDED, "Paid order refunded");
        orders.cancel(buyer, paid.id());
        check(catalog.available("book") == 10 && catalog.available("keyboard") == 5,
                "Stock restored exactly once");
        expect(IllegalStateException.class, () -> orders.ship(admin, paid.id(), "INVALID", Duration.ofDays(1)));

        section("4. Declined payment and unavailable stock preserve cart/inventory");
        cart.add("book", 1);
        expect(IllegalStateException.class, () -> orders.checkout(buyer, cart, address,
                new CardPayment(false), "declined"));
        check(catalog.available("book") == 10 && cart.snapshot().get("book") == 1,
                "Decline rolls inventory back and retains cart");
        cart.add("keyboard", 6);
        expect(IllegalStateException.class, () -> orders.checkout(buyer, cart, address, card, "no-stock"));
        check(catalog.available("book") == 10 && catalog.available("keyboard") == 5,
                "Multi-item stock check is all-or-nothing");
        cart.remove("keyboard");

        section("5. COD shipment, tracking, delivery, and cancellation restriction");
        Order cod = orders.checkout(buyer, cart, address, new CashOnDelivery(), "checkout-cod");
        check(cod.paymentStatus() == PaymentStatus.AWAITING_COLLECTION, "COD is unpaid at checkout");
        orders.ship(admin, cod.id(), "TRACK-123", Duration.ofDays(2));
        System.out.println("  Tracking: " + orders.get(buyer, cod.id()).shipment());
        expect(IllegalStateException.class, () -> orders.cancel(buyer, cod.id()));
        expect(IllegalStateException.class, () -> orders.deliver(admin, cod.id(), false));
        check(orders.deliver(admin, cod.id(), true).paymentStatus() == PaymentStatus.PAID,
                "COD paid on delivery");
        expect(IllegalStateException.class, () -> orders.ship(admin, cod.id(), "AGAIN", Duration.ofDays(1)));

        section("6. Bank payment, listing deletion, review, blocked account");
        cart.add("keyboard", 1);
        Order bank = orders.checkout(buyer, cart, address, new BankTransfer(), "checkout-bank");
        catalog.removeProduct(seller, "keyboard");
        check(catalog.searchByName("keyboard").isEmpty(), "Removed listing is hidden");
        orders.cancel(buyer, bank.id());
        check(catalog.available("keyboard") == 5, "Historical order can cancel after listing removal");
        catalog.review(buyer, "book", 5, "Clear examples");
        catalog.review(buyer, "book", 4, "Updated review");
        check(catalog.reviews("book").size() == 1, "One editable review per user/product");
        expect(IllegalArgumentException.class, () -> catalog.review(buyer, "book", 6, "Invalid"));
        Account blocked = account("blocked", BUYER);
        ShoppingCart blockedCart = new ShoppingCart(blocked);
        blockedCart.add("book", 1);
        blocked.block(admin);
        expect(IllegalStateException.class, () -> orders.checkout(blocked, blockedCart, address, card, "blocked"));
        check(orders.history(buyer).size() == 3, "Failed checkouts create no orders");

        section("7. Failed notification cannot undo a successful purchase");
        NotificationService failingNotifications = new NotificationService(List.of(
                (id, text) -> {
                    throw new IllegalStateException("Email temporarily unavailable");
                }));
        OrderService resilient = new OrderService(catalog, failingNotifications, Clock.systemUTC());
        ShoppingCart anotherCart = new ShoppingCart(otherBuyer);
        anotherCart.add("book", 1);
        Order retained = resilient.checkout(otherBuyer, anotherCart, address, new BankTransfer(), "notify");
        check(retained.status() == OrderStatus.CONFIRMED && failingNotifications.failures().size() == 1,
                "Purchase retained and failed notification recorded");

        section("8. Concurrent buyers compete for the last item");
        verifyLastItemRace(admin, seller, address);
        System.out.println("\nALL CHECKS PASSED");
    }

    private static void verifyLastItemRace(Account admin, Account seller, Address address) throws Exception {
        Catalog catalog = new Catalog();
        catalog.saveCategory(admin, new Category("limited", "Limited stock"));
        catalog.addProduct(seller, new Product("last", seller.id(), "Last item", "limited", Money.of("10")), 1);
        OrderService orders = new OrderService(catalog, new NotificationService(List.of()), Clock.systemUTC());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                Account buyer = account("racer-" + i, BUYER);
                ShoppingCart cart = new ShoppingCart(buyer);
                cart.add("last", 1);
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        orders.checkout(buyer, cart, address, new BankTransfer(), "race");
                        successes.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        check(expected.getMessage().startsWith("Insufficient stock"), "Expected stock conflict");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            check(successes.get() == 1 && catalog.available("last") == 0, "Only one buyer succeeds");
            System.out.println("  8 simultaneous buyers; exactly 1 purchase; stock = 0");
        } finally {
            pool.shutdownNow();
        }
    }

    private static Account account(String id, Account.Role... roles) {
        return new Account(id, id, Set.of(roles));
    }

    private static void section(String text) {
        System.out.println("\n" + text);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (!type.isInstance(failure)) throw new AssertionError("Unexpected failure", failure);
            System.out.println("  Expected rejection: " + failure.getMessage());
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
