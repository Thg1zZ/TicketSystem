package dev.thiagogomes.ticketsystem.reservation;

import dev.thiagogomes.ticketsystem.event.EventRepository;
import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.reservation.strategy.SeatReservationStrategy;
import dev.thiagogomes.ticketsystem.shared.error.BusinessRuleException;
import dev.thiagogomes.ticketsystem.shared.error.ConflictException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HoldService {

    private static final String ACTIVE_HOLD_MESSAGE = "User already has an active hold for this event";

    private final SeatHoldRepository holdRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final SeatReservationStrategy reservationStrategy;
    private final Clock clock;
    private final Duration holdTtl;

    public HoldService(SeatHoldRepository holdRepository,
                       SeatRepository seatRepository,
                       EventRepository eventRepository,
                       SeatReservationStrategy reservationStrategy,
                       Clock clock,
                       @Value("${ticket.hold.ttl}") Duration holdTtl) {
        this.holdRepository = holdRepository;
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
        this.reservationStrategy = reservationStrategy;
        this.clock = clock;
        this.holdTtl = holdTtl;
    }

    @Transactional
    public HoldResponse create(Long eventId, String userId, List<Long> seatIds) {
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new BusinessRuleException("Seat ids must not repeat");
        }
        if (!eventRepository.existsById(eventId)) {
            throw new NotFoundException("Event %d not found".formatted(eventId));
        }

        Instant now = clock.instant();
        expirePreviousHoldOrReject(eventId, userId, now);

        SeatHold hold = persistNewHold(new SeatHold(eventId, userId, now.plus(holdTtl)));
        List<Seat> seats = reservationStrategy.reserve(eventId, seatIds, hold, now);
        return HoldResponse.of(hold, seats);
    }

    @Transactional
    public void release(UUID holdId, String userId) {
        SeatHold hold = holdRepository.findByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new NotFoundException("Hold %s not found".formatted(holdId)));
        if (hold.getStatus() == HoldStatus.CONVERTED) {
            throw new ConflictException("Hold was already converted into an order");
        }
        closeHold(holdId, HoldStatus.RELEASED);
    }

    /**
     * Physical cleanup only: availability already treats expired holds as free.
     */
    @Transactional
    public int releaseExpired() {
        Instant now = clock.instant();
        int releasedSeats = seatRepository.releaseExpired(now);
        holdRepository.expireDue(now);
        return releasedSeats;
    }

    private void expirePreviousHoldOrReject(Long eventId, String userId, Instant now) {
        holdRepository.findByEventIdAndUserIdAndStatus(eventId, userId, HoldStatus.ACTIVE).ifPresent(previous -> {
            if (previous.isActiveAt(now)) {
                throw new ConflictException(ACTIVE_HOLD_MESSAGE, Map.of("holdId", previous.getId()));
            }
            closeHold(previous.getId(), HoldStatus.EXPIRED);
        });
    }

    private void closeHold(UUID holdId, HoldStatus finalStatus) {
        if (holdRepository.transition(holdId, HoldStatus.ACTIVE, finalStatus) == 1) {
            seatRepository.releaseHeldBy(holdId);
        }
    }

    private SeatHold persistNewHold(SeatHold hold) {
        try {
            return holdRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException raceOnActiveHold) {
            // ux_seat_hold_active_user: the same user created another hold concurrently
            throw new ConflictException(ACTIVE_HOLD_MESSAGE);
        }
    }
}
