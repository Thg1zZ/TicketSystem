package dev.thiagogomes.ticketsystem.reservation;

import dev.thiagogomes.ticketsystem.event.EventRepository;
import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.reservation.strategy.SeatReservationStrategy;
import dev.thiagogomes.ticketsystem.shared.error.BusinessRuleException;
import dev.thiagogomes.ticketsystem.shared.error.ConflictException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);
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
        log.info("Seat reservation strategy: {}", reservationStrategy.getClass().getSimpleName());
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
        SeatHold hold = findOwned(holdId, userId);
        if (hold.getStatus() == HoldStatus.CONVERTED) {
            throw new ConflictException("Hold was already converted into an order");
        }
        closeHold(hold, HoldStatus.ACTIVE, HoldStatus.RELEASED);
    }

    /**
     * Locks the hold for checkout: once CONVERTED it can no longer be released by the user or expired by the job.
     * The seats keep their own deadline, so a payment slower than the hold still loses them (checked at confirmation).
     */
    @Transactional
    public SeatHold convertForCheckout(UUID holdId, String userId) {
        SeatHold hold = findOwned(holdId, userId);
        boolean converted = hold.isActiveAt(clock.instant())
                && holdRepository.transition(holdId, HoldStatus.ACTIVE, HoldStatus.CONVERTED) == 1;
        if (!converted) {
            throw new ConflictException("Hold is not active: it expired, was released or was already checked out");
        }
        return hold;
    }

    @Transactional
    public void releaseAfterFailedCheckout(UUID holdId) {
        holdRepository.findById(holdId).ifPresent(hold -> closeHold(hold, HoldStatus.CONVERTED, HoldStatus.RELEASED));
    }

    /**
     * Lets strategies with external state (Redis) free seats that left the hold by another path (cancellation).
     */
    public void forgetSeats(UUID holdId, List<Long> seatIds) {
        holdRepository.findById(holdId).ifPresent(hold -> reservationStrategy.release(hold, seatIds));
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
            closeHold(previous, HoldStatus.ACTIVE, HoldStatus.EXPIRED);
        });
    }

    private SeatHold findOwned(UUID holdId, String userId) {
        return holdRepository.findByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new NotFoundException("Hold %s not found".formatted(holdId)));
    }

    private void closeHold(SeatHold hold, HoldStatus from, HoldStatus to) {
        if (holdRepository.transition(hold.getId(), from, to) == 1) {
            List<Long> seatIds = seatRepository.findIdsHeldBy(hold.getId());
            seatRepository.releaseHeldBy(hold.getId());
            reservationStrategy.release(hold, seatIds);
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
