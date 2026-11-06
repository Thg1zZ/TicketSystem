package dev.thiagogomes.ticketsystem.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    Optional<SeatHold> findByEventIdAndUserIdAndStatus(Long eventId, String userId, HoldStatus status);

    Optional<SeatHold> findByIdAndUserId(UUID id, String userId);

    /**
     * Conditional transition: only one concurrent caller can move a hold out of a given status.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SeatHold h set h.status = :to where h.id = :holdId and h.status = :from")
    int transition(UUID holdId, HoldStatus from, HoldStatus to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SeatHold h set h.status = dev.thiagogomes.ticketsystem.reservation.HoldStatus.EXPIRED
            where h.status = dev.thiagogomes.ticketsystem.reservation.HoldStatus.ACTIVE and h.expiresAt <= :now
            """)
    int expireDue(Instant now);
}
