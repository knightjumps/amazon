package amazon.cart;

import amazon.account.Account;
import java.util.LinkedHashMap;
import java.util.Map;
import static amazon.account.Account.Role.BUYER;

/** Offer ID -> quantity avoids duplicate lines and gives constant-time item updates. */
public final class ShoppingCart {
    private final Account owner;
    private final Map<String, Integer> items = new LinkedHashMap<>();

    public ShoppingCart(Account owner) {
        owner.require(BUYER);
        this.owner = owner;
    }
    public String ownerId() { return owner.id(); }
    public synchronized void add(String productId, int quantity) {
        owner.require(BUYER);
        validate(productId, quantity);
        items.merge(productId, quantity, Math::addExact);
    }
    public synchronized void changeQuantity(String productId, int quantity) {
        owner.require(BUYER);
        validate(productId, quantity);
        if (!items.containsKey(productId)) throw new IllegalArgumentException("Item not in cart");
        items.put(productId, quantity);
    }
    public synchronized void remove(String productId) { owner.require(BUYER); items.remove(productId); }
    public synchronized Map<String, Integer> snapshot() { return Map.copyOf(items); }
    /** Called by checkout after authorization; completion must survive a concurrent block. */
    public synchronized void clear() { items.clear(); }
    private void validate(String id, int quantity) {
        if (id == null || id.isBlank() || quantity <= 0)
            throw new IllegalArgumentException("Invalid item or quantity");
    }
}
