package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * A single "UPDATE ... WHERE available" lets Postgres do check-and-set atomically; fewer rows updated
 * than requested means someone else got at least one seat (the transaction rollback undoes the rest).
 */
@Component
@ConditionalOnProperty(name = SeatReservationStrategy.PROPERTY, havingValue = "conditional")
class ConditionalUpdateSeatReservation implements SeatReservationStrategy {

    private final SeatRepository seatRepository;

    ConditionalUpdateSeatReservation(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    public List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now) {
        List<Seat> seats = seatRepository.findForEvent(eventId, seatIds);
        SeatClaims.ensureAllFound(eventId, seatIds, seats);
        SeatClaims.ensureAvailable(seats, now);

        int claimed = seatRepository.claimIfAvailable(seatIds, hold.getId(), hold.getExpiresAt(), now);
        if (claimed != seatIds.size()) {
            // ponytail: reports every requested seat; pinpointing the lost one needs a re-read after rollback
            throw new SeatsUnavailableException(seatIds);
        }
        return seats;
    }
}
