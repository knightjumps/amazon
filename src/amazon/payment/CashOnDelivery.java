package amazon.payment;

import amazon.common.Money;

public final class CashOnDelivery implements PaymentMethod {
    @Override
    public PaymentStatus pay(Money amount) {
        return PaymentStatus.AWAITING_COLLECTION;
    }

    @Override
    public void refund(Money amount) {
        throw new IllegalStateException("Cash not collected");
    }
}
