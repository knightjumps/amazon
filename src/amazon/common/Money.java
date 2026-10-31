package amazon.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Single-currency (INR) exercise; never use floating point for prices. */
public record Money(BigDecimal amount) {
    public static final Money ZERO = Money.of("0");

    public Money {
        Objects.requireNonNull(amount);
        if (amount.signum() < 0) throw new IllegalArgumentException("Negative amount");
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }
    public static Money of(String value) { return new Money(new BigDecimal(value)); }
    public Money add(Money other) { return new Money(amount.add(other.amount)); }
    public Money multiply(int quantity) {
        if (quantity < 0) throw new IllegalArgumentException("Negative quantity");
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)));
    }
    @Override public String toString() { return "INR " + amount.toPlainString(); }
}
