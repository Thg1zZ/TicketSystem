package dev.thiagogomes.ticketsystem.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("""
            select new dev.thiagogomes.ticketsystem.event.SeatView(
                s.id, s.sector.id, s.rowLabel, s.seatNumber, s.status, s.heldUntil)
            from Seat s
            where s.sector.event.id = :eventId
            order by s.sector.id, s.rowLabel, s.seatNumber
            """)
    List<SeatView> findSeatMap(Long eventId);

    /**
     * Row lock on the seats only ("OF s"), always in id order so two buyers never wait on each other in a cycle.
     */
    @Query(value = """
            SELECT s.* FROM seat s
            JOIN sector sec ON sec.id = s.sector_id
            WHERE s.id IN (:seatIds) AND sec.event_id = :eventId
            ORDER BY s.id
            FOR UPDATE OF s
            """, nativeQuery = true)
    List<Seat> lockForUpdate(Long eventId, Collection<Long> seatIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Seat s
            set s.status = dev.thiagogomes.ticketsystem.event.SeatStatus.AVAILABLE,
                s.holdId = null, s.heldUntil = null, s.version = s.version + 1
            where s.holdId = :holdId and s.status = dev.thiagogomes.ticketsystem.event.SeatStatus.HELD
            """)
    int releaseHeldBy(UUID holdId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Seat s
            set s.status = dev.thiagogomes.ticketsystem.event.SeatStatus.AVAILABLE,
                s.holdId = null, s.heldUntil = null, s.version = s.version + 1
            where s.status = dev.thiagogomes.ticketsystem.event.SeatStatus.HELD and s.heldUntil <= :now
            """)
    int releaseExpired(Instant now);
}
