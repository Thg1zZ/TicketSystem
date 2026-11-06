package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;

import java.time.Instant;
import java.util.List;

/**
 * SELECT ... FOR UPDATE: competing transactions queue on the row lock, so the
 * availability check and the write happen as one step. Simple and safe; the price is waiting.
 */
class PessimisticSeatReservation implements SeatReservationStrategy {

    private final SeatRepository seatRepository;

    PessimisticSeatReservation(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    public List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now) {
        List<Seat> seats = seatRepository.lockForUpdate(eventId, seatIds);
        SeatClaims.ensureAllFound(eventId, seatIds, seats);
        SeatClaims.ensureAvailable(seats, now);

        seats.forEach(seat -> seat.hold(hold.getId(), hold.getExpiresAt()));
        return seats;
    }
}
