package amazon.order;

import java.time.Instant;

/** One shipment per order in the core interview scope. */
public record Shipment(String trackingId, Instant estimatedArrival, Status status) {
    public enum Status { IN_TRANSIT, DELIVERED }
}
