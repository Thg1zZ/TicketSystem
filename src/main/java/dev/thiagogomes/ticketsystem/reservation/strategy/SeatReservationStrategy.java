package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;

import java.time.Instant;
import java.util.List;

/**
 * Atomically claims every requested seat for the hold, or none of them.
 * Runs inside the caller's transaction; the hold row is already persisted.
 *
 * @throws dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException if any seat is taken
 * @throws dev.thiagogomes.ticketsystem.shared.error.NotFoundException if a seat does not belong to the event
 */
public interface SeatReservationStrategy {

    List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now);
}
