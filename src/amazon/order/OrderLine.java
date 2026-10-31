package amazon.order;

import amazon.common.Money;

/** Historical snapshot, independent of future listing edits and deletion. */
public record OrderLine(String productId, String sellerId, String title, Money unitPrice, int quantity) {
    public OrderLine {
        if (productId == null || sellerId == null || title == null || unitPrice == null || quantity <= 0)
            throw new IllegalArgumentException("Invalid order line");
    }
    public Money subtotal() { return unitPrice.multiply(quantity); }
}
