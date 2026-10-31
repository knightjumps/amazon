# Amazon-like online shopping — interview LLD

An original, runnable Java 17 design for a **60–80 minute object-oriented design interview**. No framework, database, dependencies, or copied course code. Run `Main` in IntelliJ, or use the commands below.

## Run

From this project directory (JDK 17+):

```sh
mkdir -p out
javac --release 17 -d out $(find src test -name '*.java')
java -cp out Main
java -cp out OrderServiceChecks
```

The driver ends with `ALL CHECKS PASSED`; the focused concurrency checks end with `ORDER SERVICE CHECKS PASSED`. These checks work without `-ea`.

## Scope decisions — say these before coding

- Guests can search without an account; registered buyers can shop and review. One account may be both buyer and seller. Admin manages categories, blocks accounts, and stands in for a trusted fulfillment worker.
- A `Product` means **one seller listing of one variant**, not a universal marketplace product. Two sellers use different listing IDs. Split Product/SKU/Offer only if the interviewer asks for shared product identity or variants.
- One cart is supplied to each checkout; the driver uses one active cart per buyer. A persistent cart registry and enforcing one active cart per account are outside this exercise.
- The cart's entire contents are checked out. One order has one address and at most one shipment. Whole-order cancellation only, before dispatch.
- Single currency: INR. Prices have at most two decimal places. Totals are item subtotals; taxes, shipping fees, and promotions are explicitly outside scope.
- Payment strategies are immediate, deterministic **simulators**. Bank transfer simulates a confirmed transfer; COD remains unpaid until delivery. No real account credentials, card data, external calls, or authentication flow.
- No HTTP endpoints: callers supply trusted authenticated `Account` objects. Account IDs are assumed unique and stable. A backend would resolve these from authentication, never accept an arbitrary client-created account or role.
- All storage is in memory. No cross-process coordination or crash recovery is claimed.

## Package architecture

```text
Main                              composition root + walkthrough
  |
  +-- account                     identity, roles, address
  +-- catalog                     categories, seller listings, inventory, reviews, search
  +-- cart                        purchase intent and quantity rules
  +-- order                       checkout/cancel/ship/deliver orchestration and immutable views
  +-- payment                     payment strategy interface + implementations
  +-- notification                delivery boundary and failure capture
  +-- common                      precise money arithmetic
```

`Main` wires dependencies explicitly through constructors. Spring dependency injection would perform the same wiring; annotations do not add value to this exercise.

## Why these classes exist

| Class | Responsibility and reason |
|---|---|
| `Account` | Identity, roles, blocked state. Roles allow the same person to buy and sell without a fragile inheritance tree. |
| `Address` | Immutable shipping value; editing another saved address cannot change an order. Postal code remains a string. |
| `Money` | Precise decimal arithmetic and nonnegative amounts; avoids floating-point totals and mixed primitive types. |
| `Category` | Admin-controlled discovery grouping independent of individual listings. |
| `Product` | Immutable seller listing and current price. Stock is separate from product metadata. |
| `Review` | Validated rating/text. Catalog keeps one editable review per author/product. Verified-purchase policy is an extension. |
| `Catalog` | Search, listing ownership checks, categories/reviews, and all-or-nothing inventory updates. Keeping inventory here gives one small concurrency boundary; split `InventoryService` when warehouse logic appears. |
| `ShoppingCart` | Mutable ID-to-quantity map. No stale prices or duplicated product metadata. Map updates are expected O(1). |
| `OrderLine` | Purchased title, seller, unit price, and quantity snapshot; catalog edits cannot rewrite purchase history. |
| `Order` | Immutable outward snapshot. Callers cannot bypass lifecycle rules with setters. Service keeps the authoritative snapshot. |
| `Shipment` | Tracking, estimated arrival, and delivery state; a confirmed COD order is not the same as a delivered/paid order. |
| `OrderService` | Coordinates checkout and lifecycle changes. Also stores orders in memory; repository interfaces can be extracted with a real database, without ceremonial abstractions now. |
| `PaymentMethod` | Strategy contract so checkout does not branch on card/bank/cash types. |
| `NotificationSender` | Channel contract; driver wires email/SMS simulators. |
| `NotificationService` | Isolates channel failures from purchase success and records failed deliveries for inspection. |

Not every noun needs a class: `CartItem` would currently duplicate a map entry `(productId, quantity)` without adding behavior. Add it when cart-line options or independent line rules appear.

## Relationships

```text
Account --roles--> BUYER / SELLER / ADMIN
Account 1 -------- * Product (seller-owned listings)
Account 1 -------- * Order
ShoppingCart ----- Map<productId, quantity>
Category 1 ------- * Product
Product 1 -------- * Review
Order 1 ---------- 1..* OrderLine
Order 1 ---------- 0..1 Shipment
OrderService -----> Catalog, PaymentMethod, NotificationService
```

Order lines are part of the order snapshot. Product/category/review relationships use IDs rather than loading cyclic object graphs. Deleting a listing hides it from discovery but retains inventory identity so historical cancellations remain valid; its ID cannot be reused.

## Checkout sequence

```text
Buyer -> OrderService: checkout(account, cart, address, payment, requestKey)
  1. Check role, blocked state, cart ownership, request parameters.
  2. Return original result for a previously completed request key.
  3. Hold cart and catalog monitors; snapshot all quantities and current prices.
  4. Validate ALL lines, then deduct ALL stock.
  5. Invoke the immediate simulated payment strategy.
     - Decline/known pre-charge exception: restore stock; retain cart; create no order.
     - Paid or COD accepted: store an immutable confirmed order.
  6. Record successful request key, clear checked-out cart, notify customer.
```

The server uses current catalog prices; this demo has no previously displayed quote to reconcile. A real UI should confirm changed prices before payment.

Successful checkout deduplication is scoped by `(buyerId, requestKey)`. In this in-memory API, retries retain the same cart/payment instances and address. A completed key denotes the original operation even if the cart is subsequently reused. Use a new key for a new purchase. Failed attempts are not cached; a known decline can be retried. A network API should use a stable cart ID, payment token, request fingerprint, and durable idempotency record instead of Java object identity.

## Lifecycle rules

```text
Order:   CONFIRMED -> SHIPPED -> DELIVERED
             |
             +----> CANCELLED

Card/bank: PAID -> REFUNDED on pre-shipment cancellation
COD:       AWAITING_COLLECTION -> PAID at delivery
                         |
                         +----> CANCELLED if cancelled before shipment
```

- Cancelling twice restores stock/refunds only once.
- Shipping and cancellation are serialized through the same service monitor: only one can win.
- COD delivery cannot finish unless cash was collected. Uncollected COD cancellation is not a refund.
- Updating a listing price or removing a listing cannot change order-line snapshots.
- `Order` values are snapshots: call `get()` again after a transition for the latest state.
- Blocking is checked at operation entry; it does not retroactively undo an already accepted operation. Fulfillment can continue for existing orders via the trusted admin actor.

## Concurrency choice and its limits

For a runnable interview exercise, use coarse-grained Java monitors rather than a database simulation. `OrderService` serializes order transitions; `Catalog` makes multi-item stock validation/deduction atomic even when shared by multiple order services; the cart lock prevents edits being lost during checkout. Lock acquisition order is service -> cart -> catalog. No reverse lock acquisition exists in this implementation.

This intentionally sacrifices throughput for a short, auditable implementation. Payment and notification adapters must remain immediate, non-reentrant simulators here. Do not replace them with slow network calls under these locks.

The payment contract assumes an exception occurs **before** any charge and a refund either succeeds or fails without side effects. Real gateways cannot generally promise this: a timeout may follow a successful charge. Production needs persistent payment attempts, provider idempotency keys, pending/unknown outcomes, reconciliation/webhooks, and compensating refunds. Likewise a process crash between payment and order storage is not recoverable here.

With a database: use atomic stock updates/reservations, transactions for local state changes, version checks for cancel/ship races, and a unique checkout-key constraint. Perform external payment calls outside database locks. Publish notifications through a transactional outbox with retry/deduplication. In-memory failed notifications are neither durable nor an automatic retry queue.

## Patterns and deliberate omissions

- **Strategy:** card/bank/COD implement one payment contract.
- **Dependency inversion:** orchestration depends on `PaymentMethod` and `NotificationSender` interfaces; the driver provides implementations.
- **Encapsulation:** lifecycle transitions are available through the service, not order setters.
- No forced Singleton, Factory, Composite, Command, or guest inheritance. Add a factory only when construction/selection becomes complicated. A map of cart items is not automatically Composite.
- No microservices, REST controllers, repository hierarchy, password system, Redis, or Kafka in the coding scope. Discuss them only when a requirement needs them.

## Complexity

For `k` distinct checkout lines, checkout is O(k) time and O(k) snapshot space, excluding adapters. Cart updates are expected O(1). Name/category search is O(n) over listings; a name/category index is a natural extension. Order lookup and successful-request lookup are expected O(1); history is O(number of orders). No hidden unbounded traversal occurs during checkout.

## Driver coverage

`Main` demonstrates category create/rename/delete, seller listings and ownership/role restrictions, guest name/category search, cart updates/removal, card payment, duplicate successful checkout, historical pricing, cancellation/refund and repeat cancellation, stock conflicts, declined payment rollback, COD tracking/delivery, post-shipment rejection, bank payment, listing removal with historical cancellation, review replacement/validation, blocked-account rejection, notification failure isolation, and an eight-buyer last-item race.

`OrderServiceChecks` additionally exercises simultaneous duplicate requests with a counted payment strategy, concurrent cancellation versus dispatch, known pre-charge exceptions, seller ownership beyond role membership, and uncollected COD cancellation.

## Present this in 60–80 minutes

| Minutes | What to do |
|---|---|
| 0–8 | Clarify scope, actors, one seller listing/variant, one shipment, payment simulation. State invariants. |
| 8–18 | Draw cart/order/product relationships; explain immutable purchased snapshots and roles. |
| 18–30 | Code account, money, listing, cart, order-line/order values. Keep peripheral records brief. |
| 30–50 | Implement checkout, all-or-nothing stock, payment strategy, and cancellation. These are the core. |
| 50–60 | Add ship/deliver, COD behavior, a notification boundary, and a happy-path driver. |
| 60–70 | Demonstrate decline, insufficient stock, cancellation restriction, and successful-request retry. |
| 70–80 | Explain concurrency, unknown payment outcomes, persistence, split shipments, and trade-offs. |

Do not transcribe the entire prepared project under time pressure. Treat catalog administration, review plumbing, and the full check harness as prepared reference material; prioritize the purchase lifecycle and invariants. If given only 60 minutes, describe peripheral operations verbally and code the core paths.

## Key takeaways

Keep purchase intent separate from historical orders. Use precise money, immutable snapshots, stock validation that cannot partially succeed, explicit ownership checks, and guarded lifecycle changes. Prefer a few justified interfaces over a pattern-heavy framework. Be candid that single-JVM synchronization and deterministic payment simulators establish interview behavior, not production durability.
