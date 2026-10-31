package amazon.catalog;

public record Review(String authorId, int rating, String text) {
    public Review {
        if (rating < 1 || rating > 5 || text == null || text.isBlank())
            throw new IllegalArgumentException("Rating must be 1..5 and review cannot be empty");
    }
}
