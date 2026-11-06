package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;
import dev.thiagogomes.ticketsystem.reservation.SeatInventoryRepository;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;

import java.time.Instant;
import java.util.List;

/**
 * A single "UPDATE ... WHERE available" lets Postgres do check-and-set atomically; fewer rows updated
 * than requested means someone else got at least one seat (the transaction rollback undoes the rest).
 */
class ConditionalUpdateSeatReservation implements SeatReservationStrategy {

    private final SeatInventoryRepository seatInventory;

    ConditionalUpdateSeatReservation(SeatInventoryRepository seatInventory) {
        this.seatInventory = seatInventory;
    }

    @Override
    public List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now) {
        List<Seat> seats = seatInventory.findForEvent(eventId, seatIds);
        SeatClaims.ensureAllFound(eventId, seatIds, seats);
        SeatClaims.ensureAvailable(seats, now);

        int claimed = seatInventory.claimIfAvailable(seatIds, hold.getId(), hold.getExpiresAt(), now);
        if (claimed != seatIds.size()) {
            // ponytail: reports every requested seat; pinpointing the lost one needs a re-read after rollback
            throw new SeatsUnavailableException(seatIds);
        }
        return seats;
    }
}
