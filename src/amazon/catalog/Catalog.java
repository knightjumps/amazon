package amazon.catalog;

import amazon.account.Account;
import amazon.common.Money;
import java.util.*;
import static amazon.account.Account.Role.*;

/** Also owns inventory for this small exercise, so a multi-item purchase is atomic. */
public final class Catalog {
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, Product> products = new LinkedHashMap<>();
    private final Map<String, Integer> stock = new HashMap<>();
    private final Map<String, Map<String, Review>> reviews = new HashMap<>();

    public synchronized void saveCategory(Account admin, Category category) {
        admin.require(ADMIN);
        categories.put(category.id(), category);
    }
    public synchronized void deleteCategory(Account admin, String id) {
        admin.require(ADMIN);
        if (products.values().stream().anyMatch(p -> p.categoryId().equals(id)))
            throw new IllegalStateException("Category still has listings");
        categories.remove(id);
    }
    public synchronized void addProduct(Account seller, Product product, int quantity) {
        seller.require(SELLER);
        if (!seller.id().equals(product.sellerId())) throw new SecurityException("Not listing owner");
        if (!categories.containsKey(product.categoryId()) || quantity < 0)
            throw new IllegalArgumentException("Invalid category or stock");
        if (products.containsKey(product.id()) || stock.containsKey(product.id()))
            throw new IllegalArgumentException("Listing ID already used");
        products.put(product.id(), product);
        stock.put(product.id(), quantity);
    }
    public synchronized void changePrice(Account seller, String id, Money price) {
        Product old = owned(seller, id);
        products.put(id, new Product(id, old.sellerId(), old.name(), old.categoryId(), price));
    }
    public synchronized void removeProduct(Account seller, String id) {
        owned(seller, id);
        products.remove(id); // Keep inventory identity for cancellation of historical orders.
    }
    private Product owned(Account seller, String id) {
        seller.require(SELLER);
        Product product = get(id);
        if (!seller.id().equals(product.sellerId())) throw new SecurityException("Not listing owner");
        return product;
    }
    public synchronized Product get(String id) {
        Product product = products.get(id);
        if (product == null) throw new IllegalArgumentException("Listing unavailable: " + id);
        return product;
    }
    // Search is public: guests need no fake Account object.
    public synchronized List<Product> searchByName(String text) {
        String query = Objects.requireNonNull(text).toLowerCase(Locale.ROOT);
        return products.values().stream()
                .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(query)).toList();
    }
    public synchronized List<Product> searchByCategory(String categoryId) {
        return products.values().stream().filter(p -> p.categoryId().equals(categoryId)).toList();
    }
    public synchronized int available(String id) { return stock.getOrDefault(id, 0); }
    public synchronized void review(Account buyer, String productId, int rating, String text) {
        buyer.require(BUYER);
        get(productId);
        reviews.computeIfAbsent(productId, ignored -> new LinkedHashMap<>())
                .put(buyer.id(), new Review(buyer.id(), rating, text));
    }
    public synchronized List<Review> reviews(String productId) {
        return List.copyOf(reviews.getOrDefault(productId, Map.of()).values());
    }
    /** Validate every line before changing any stock. Caller holds this monitor through checkout. */
    public synchronized void reserve(Map<String, Integer> quantities) {
        quantities.forEach((id, quantity) -> {
            get(id);
            if (quantity <= 0 || available(id) < quantity)
                throw new IllegalStateException("Insufficient stock: " + id);
        });
        quantities.forEach((id, quantity) -> stock.put(id, stock.get(id) - quantity));
    }
    public synchronized void release(Map<String, Integer> quantities) {
        quantities.forEach((id, quantity) -> {
            if (quantity <= 0 || !stock.containsKey(id)) throw new IllegalArgumentException("Invalid release");
        });
        quantities.forEach((id, quantity) -> stock.put(id, Math.addExact(stock.get(id), quantity)));
    }
}
