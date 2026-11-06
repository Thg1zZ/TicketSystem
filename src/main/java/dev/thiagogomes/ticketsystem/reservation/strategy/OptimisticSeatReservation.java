package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Read without locking, then write with "WHERE version = ?" (@Version). Nobody waits; whoever commits
 * second gets 0 rows updated. No retry: a conflict here means another buyer already owns the seat.
 */
@Component
@ConditionalOnProperty(name = SeatReservationStrategy.PROPERTY, havingValue = "optimistic")
class OptimisticSeatReservation implements SeatReservationStrategy {

    private final SeatRepository seatRepository;

    OptimisticSeatReservation(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    public List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now) {
        List<Seat> seats = seatRepository.findForEvent(eventId, seatIds);
        SeatClaims.ensureAllFound(eventId, seatIds, seats);
        SeatClaims.ensureAvailable(seats, now);

        seats.forEach(seat -> seat.hold(hold.getId(), hold.getExpiresAt()));
        try {
            seatRepository.flush();
        } catch (ObjectOptimisticLockingFailureException lostRace) {
            throw new SeatsUnavailableException(lostRace.getIdentifier() instanceof Long seatId
                    ? List.of(seatId)
                    : SeatClaims.ids(seats));
        }
        return seats;
    }
}
