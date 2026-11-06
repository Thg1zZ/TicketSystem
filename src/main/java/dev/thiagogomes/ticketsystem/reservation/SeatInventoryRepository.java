package dev.thiagogomes.ticketsystem.reservation;

import dev.thiagogomes.ticketsystem.event.Seat;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Every seat state transition (AVAILABLE -> HELD -> SOLD -> AVAILABLE) lives here, as conditional statements
 * that stay correct under concurrency. Extends {@link Repository}, not JpaRepository: only these operations exist.
 */
public interface SeatInventoryRepository extends Repository<Seat, Long> {

    String STATUS = "dev.thiagogomes.ticketsystem.event.SeatStatus.";

    /**
     * JPQL twin of {@code SeatStatus.effective()}: an expired hold counts as free. Keep both in sync
     * (SeatStatusTest covers the Java side, ReservationStrategyContract the SQL side).
     */
    String CLAIMABLE = "(s.status = " + STATUS + "AVAILABLE or (s.status = " + STATUS + "HELD and s.heldUntil <= :now))";
    String HELD_BY_HOLD = "s.holdId = :holdId and s.status = " + STATUS + "HELD";
    String MAKE_AVAILABLE = "s.status = " + STATUS + "AVAILABLE, s.holdId = null, s.heldUntil = null, s.version = s.version + 1";

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

    /**
     * Plain read, no lock. Sector is fetched so the response can be built from detached entities.
     */
    @Query("""
            select s from Seat s join fetch s.sector sec
            where s.id in :seatIds and sec.event.id = :eventId
            order by s.id
            """)
    List<Seat> findForEvent(Long eventId, Collection<Long> seatIds);

    /**
     * Writes pending changes; the optimistic strategy uses it to surface version conflicts inside the strategy.
     */
    void flush();

    /**
     * Check-and-set in a single statement: concurrent UPDATEs on the same row wait for each other and the loser
     * re-evaluates the WHERE against the winner's committed row, matching nothing. Callers compare the row count.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Seat s set s.status = " + STATUS + "HELD, s.holdId = :holdId, s.heldUntil = :heldUntil,"
            + " s.version = s.version + 1 where s.id in :seatIds and " + CLAIMABLE)
    int claimIfAvailable(Collection<Long> seatIds, UUID holdId, Instant heldUntil, Instant now);

    @Query("select s.id from Seat s where " + HELD_BY_HOLD)
    List<Long> findIdsHeldBy(UUID holdId);

    @Query("select s from Seat s join fetch s.sector where " + HELD_BY_HOLD + " order by s.id")
    List<Seat> findHeldBy(UUID holdId);

    /**
     * Seats still pointing at the hold, whatever their state (HELD while active, SOLD after checkout).
     */
    @Query("select s from Seat s join fetch s.sector where s.holdId = :holdId order by s.id")
    List<Seat> findAssignedTo(UUID holdId);

    /**
     * Succeeds only for seats this hold still owns; a seat taken over after the hold deadline is not counted.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Seat s set s.status = " + STATUS + "SOLD, s.heldUntil = null, s.version = s.version + 1"
            + " where " + HELD_BY_HOLD)
    int sellHeldBy(UUID holdId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Seat s set " + MAKE_AVAILABLE + " where s.id in :seatIds and s.status = " + STATUS + "SOLD")
    int releaseSold(Collection<Long> seatIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Seat s set " + MAKE_AVAILABLE + " where " + HELD_BY_HOLD)
    int releaseHeldBy(UUID holdId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Seat s set " + MAKE_AVAILABLE + " where s.status = " + STATUS + "HELD and s.heldUntil <= :now")
    int releaseExpired(Instant now);
}
