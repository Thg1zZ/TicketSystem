package dev.thiagogomes.ticketsystem.ticketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    /**
     * A redelivered message hits uk_ticket_order_seat and inserts nothing: returns 0 instead of failing.
     */
    @Modifying
    @Query(value = """
            INSERT INTO ticket (id, order_id, seat_id, code, status)
            VALUES (:id, :orderId, :seatId, :code, 'VALID')
            ON CONFLICT (order_id, seat_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID id, UUID orderId, Long seatId, String code);

    List<Ticket> findByOrderIdOrderBySeatId(UUID orderId);

    @Query("""
            select t from Ticket t
            where t.id = :ticketId
              and exists (select 1 from CustomerOrder o where o.id = t.orderId and o.userId = :userId)
            """)
    Optional<Ticket> findOwned(UUID ticketId, String userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Ticket t set t.status = dev.thiagogomes.ticketsystem.ticketing.TicketStatus.CANCELLED
            where t.orderId = :orderId and t.status = dev.thiagogomes.ticketsystem.ticketing.TicketStatus.VALID
            """)
    int cancelForOrder(UUID orderId);
}
