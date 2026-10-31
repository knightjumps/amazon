package amazon.catalog;

import amazon.common.Money;
import java.util.Objects;

/** One seller listing, one variant. Stock is maintained by Catalog, not this value. */
public record Product(String id, String sellerId, String name, String categoryId, Money price) {
    public Product {
        if (id == null || id.isBlank() || sellerId == null || sellerId.isBlank()
                || name == null || name.isBlank() || categoryId == null || categoryId.isBlank())
            throw new IllegalArgumentException("Invalid listing");
        Objects.requireNonNull(price);
    }
}
