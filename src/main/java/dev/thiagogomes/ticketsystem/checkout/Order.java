package dev.thiagogomes.ticketsystem.checkout;

import dev.thiagogomes.ticketsystem.event.Seat;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * "Order" is a reserved word in JPQL, hence the entity name.
 */
@Entity(name = "CustomerOrder")
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "hold_id", nullable = false)
    private UUID holdId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("seatId")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    /**
     * Items start inactive: a seat only counts as sold once the payment is confirmed.
     */
    static Order place(String userId, UUID holdId, String idempotencyKey, List<Seat> seats, Instant now) {
        Order order = new Order();
        order.userId = userId;
        order.holdId = holdId;
        order.idempotencyKey = idempotencyKey;
        order.createdAt = now;
        order.updatedAt = now;
        seats.forEach(seat -> order.items.add(new OrderItem(order, seat.getId(), seat.getSector().getPrice())));
        order.totalAmount = order.items.stream().map(OrderItem::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        return order;
    }

    boolean isSettled() {
        return status != OrderStatus.PENDING_PAYMENT;
    }

    boolean belongsToHold(UUID otherHoldId) {
        return holdId.equals(otherHoldId);
    }

    /**
     * The gateway deduplicates by this key; the order id is unique, unlike the client's key, which is per user.
     */
    String paymentKey() {
        return id.toString();
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
