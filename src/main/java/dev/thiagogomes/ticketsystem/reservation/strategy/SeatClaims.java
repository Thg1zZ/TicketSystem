package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks shared by every strategy.
 */
final class SeatClaims {

    private SeatClaims() {
    }

    static void ensureAllFound(Long eventId, List<Long> requested, List<Seat> found) {
        if (found.size() == requested.size()) {
            return;
        }
        List<Long> missing = new ArrayList<>(requested);
        found.forEach(seat -> missing.remove(seat.getId()));
        throw new NotFoundException("Seats %s not found in event %d".formatted(missing, eventId));
    }

    static void ensureAvailable(List<Seat> seats, Instant now) {
        List<Long> unavailable = seats.stream().filter(seat -> !seat.isAvailableAt(now)).map(Seat::getId).toList();
        if (!unavailable.isEmpty()) {
            throw new SeatsUnavailableException(unavailable);
        }
    }

    static List<Long> ids(List<Seat> seats) {
        return seats.stream().map(Seat::getId).toList();
    }
}
