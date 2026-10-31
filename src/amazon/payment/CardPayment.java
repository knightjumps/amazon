package amazon.payment;

import amazon.common.Money;

/**
 * Deterministic simulator; no card numbers or security codes are stored.
 */
public final class CardPayment implements PaymentMethod {
    private final boolean approved;

    public CardPayment(boolean approved) {
        this.approved = approved;
    }

    @Override
    public PaymentStatus pay(Money amount) {
        return approved ? PaymentStatus.PAID : PaymentStatus.DECLINED;
    }

    @Override
    public void refund(Money amount) { /* Simulated successful refund. */ }
}
