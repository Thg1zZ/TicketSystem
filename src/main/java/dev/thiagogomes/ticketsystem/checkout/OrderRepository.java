package dev.thiagogomes.ticketsystem.checkout;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsByIdAndUserId(UUID id, String userId);

    /**
     * SELECT ... FOR UPDATE: serializes ticket issuance and cancellation of the same order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :orderId")
    Optional<Order> lockById(UUID orderId);

    /**
     * Conditional transition: among concurrent finalizers of the same order, exactly one gets 1.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CustomerOrder o set o.status = :to, o.updatedAt = :now where o.id = :orderId and o.status = :from")
    int transition(UUID orderId, OrderStatus from, OrderStatus to, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CustomerOrder o
            set o.status = dev.thiagogomes.ticketsystem.checkout.OrderStatus.CONFIRMED,
                o.paymentReference = :paymentReference, o.updatedAt = :now
            where o.id = :orderId and o.status = dev.thiagogomes.ticketsystem.checkout.OrderStatus.PENDING_PAYMENT
            """)
    int confirm(UUID orderId, String paymentReference, Instant now);

    @Query("""
            select o.id from CustomerOrder o
            where o.status = dev.thiagogomes.ticketsystem.checkout.OrderStatus.PENDING_PAYMENT and o.updatedAt < :before
            """)
    List<UUID> findPendingIdsUpdatedBefore(Instant before);

    @Query("""
            select e.startsAt from CustomerOrder o, SeatHold h, Event e
            where o.id = :orderId and h.id = o.holdId and e.id = h.eventId
            """)
    Optional<Instant> findEventStart(UUID orderId);
}
