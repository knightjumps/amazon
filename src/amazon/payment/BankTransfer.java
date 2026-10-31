package amazon.payment;

import amazon.common.Money;

/**
 * Simulates a confirmed transfer; asynchronous bank settlement is an extension.
 */
public final class BankTransfer implements PaymentMethod {
    @Override
    public PaymentStatus pay(Money amount) {
        return PaymentStatus.PAID;
    }

    @Override
    public void refund(Money amount) { /* Simulated successful refund. */ }
}
