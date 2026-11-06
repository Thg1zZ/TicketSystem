package dev.thiagogomes.ticketsystem.checkout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * Fails with a unique violation (ux_order_item_active_seat) if any seat is already sold in another order.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderItem i set i.active = :active where i.order.id = :orderId")
    int setActive(UUID orderId, boolean active);
}
