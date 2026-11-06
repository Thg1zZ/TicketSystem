package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;

import java.time.Instant;
import java.util.List;

/**
 * Atomically claims every requested seat for the hold, or none of them.
 * Runs inside the caller's transaction; the hold row is already persisted.
 * The active implementation is chosen by {@code ticket.reservation.strategy}.
 *
 * @throws dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException if any seat is taken
 * @throws dev.thiagogomes.ticketsystem.shared.error.NotFoundException if a seat does not belong to the event
 */
public interface SeatReservationStrategy {

    String PROPERTY = "ticket.reservation.strategy";

    List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now);

    /**
     * Optional fast path, called BEFORE any database transaction: reject requests already known to lose.
     * Only an optimization — a seat passing here can still be refused by {@link #reserve}.
     */
    default void rejectIfKnownTaken(Long eventId, List<Long> seatIds, Instant now) {
    }

    /**
     * Called when a hold ends before expiring (released or replaced). Only strategies with state outside
     * the database need it.
     */
    default void release(SeatHold hold, List<Long> seatIds) {
    }
}
