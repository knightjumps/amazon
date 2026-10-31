package amazon.payment;

import amazon.common.Money;

/**
 * Strategy: implementations either return a known outcome or fail before any charge.
 */
public interface PaymentMethod {
    PaymentStatus pay(Money amount);

    void refund(Money amount);
}
