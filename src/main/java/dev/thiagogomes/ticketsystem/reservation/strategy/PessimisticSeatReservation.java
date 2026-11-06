package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SELECT ... FOR UPDATE: competing transactions queue on the row lock, so the
 * availability check and the write happen as one step. Simple and safe; the price is waiting.
 */
@Component
class PessimisticSeatReservation implements SeatReservationStrategy {

    private final SeatRepository seatRepository;

    PessimisticSeatReservation(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    public List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now) {
        List<Seat> seats = seatRepository.lockForUpdate(eventId, seatIds);
        ensureAllFound(eventId, seatIds, seats);

        List<Long> unavailable = seats.stream().filter(seat -> !seat.isAvailableAt(now)).map(Seat::getId).toList();
        if (!unavailable.isEmpty()) {
            throw new SeatsUnavailableException(unavailable);
        }

        seats.forEach(seat -> seat.hold(hold.getId(), hold.getExpiresAt()));
        return seats;
    }

    private static void ensureAllFound(Long eventId, List<Long> requested, List<Seat> found) {
        if (found.size() == requested.size()) {
            return;
        }
        List<Long> missing = new ArrayList<>(requested);
        found.forEach(seat -> missing.remove(seat.getId()));
        throw new NotFoundException("Seats %s not found in event %d".formatted(missing, eventId));
    }
}
