package amazon.notification;

import java.util.ArrayList;
import java.util.List;

/**
 * Delivery failure must not undo a completed business operation.
 */
public final class NotificationService {
    public record FailedDelivery(String accountId, String message, NotificationSender sender) {
    }

    private final List<NotificationSender> senders;
    private final List<FailedDelivery> failures = new ArrayList<>();

    public NotificationService(List<NotificationSender> senders) {
        this.senders = List.copyOf(senders);
    }

    public synchronized void publish(String accountId, String message) {
        for (NotificationSender sender : senders) {
            try {
                sender.send(accountId, message);
            } catch (RuntimeException failure) {
                failures.add(new FailedDelivery(accountId, message, sender));
            }
        }
    }

    public synchronized List<FailedDelivery> failures() {
        return List.copyOf(failures);
    }
}
