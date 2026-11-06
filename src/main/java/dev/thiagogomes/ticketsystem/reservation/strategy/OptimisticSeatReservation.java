package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;
import dev.thiagogomes.ticketsystem.reservation.SeatInventoryRepository;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;

/**
 * Read without locking, then write with "WHERE version = ?" (@Version). Nobody waits; whoever commits
 * second gets 0 rows updated. No retry: a conflict here means another buyer already owns the seat.
 */
class OptimisticSeatReservation implements SeatReservationStrategy {

    private final SeatInventoryRepository seatInventory;

    OptimisticSeatReservation(SeatInventoryRepository seatInventory) {
        this.seatInventory = seatInventory;
    }

    @Override
    public List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now) {
        List<Seat> seats = seatInventory.findForEvent(eventId, seatIds);
        SeatClaims.ensureAllFound(eventId, seatIds, seats);
        SeatClaims.ensureAvailable(seats, now);

        seats.forEach(seat -> seat.hold(hold.getId(), hold.getExpiresAt()));
        try {
            seatInventory.flush();
        } catch (ObjectOptimisticLockingFailureException lostRace) {
            throw new SeatsUnavailableException(lostRace.getIdentifier() instanceof Long seatId
                    ? List.of(seatId)
                    : SeatClaims.ids(seats));
        }
        return seats;
    }
}
