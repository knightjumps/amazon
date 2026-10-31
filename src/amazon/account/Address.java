package amazon.account;

/** An immutable address is safe to snapshot into an order. Postal codes are strings. */
public record Address(String street, String city, String postalCode, String country) {
    public Address {
        if (street == null || street.isBlank() || city == null || city.isBlank()
                || postalCode == null || postalCode.isBlank() || country == null || country.isBlank())
            throw new IllegalArgumentException("Incomplete shipping address");
    }
}
