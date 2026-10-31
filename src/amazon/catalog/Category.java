package amazon.catalog;

public record Category(String id, String name) {
    public Category {
        if (id == null || id.isBlank() || name == null || name.isBlank())
            throw new IllegalArgumentException("Invalid category");
    }
}
