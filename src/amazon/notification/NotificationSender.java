package amazon.notification;

/** A channel boundary; production adapters can implement email, SMS or push. */
@FunctionalInterface
public interface NotificationSender {
    void send(String accountId, String message);
}
