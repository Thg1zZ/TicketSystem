package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.MutableClock;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Behavior every strategy must honor. Each subclass runs it against a real Postgres/Redis with one strategy active.
 */
abstract class ReservationStrategyContract {

    static final int BUYERS = 50;

    @Autowired
    HoldService holdService;
    @Autowired
    EventService eventService;
    @Autowired
    SeatRepository seatRepository;
    @Autowired
    SeatReservationStrategy activeStrategy;
    @Autowired
    MutableClock clock;

    abstract Class<? extends SeatReservationStrategy> expectedStrategy();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void configuredStrategyIsActive() {
        assertThat(activeStrategy).isInstanceOf(expectedStrategy());
    }

    @Test
    void exactlyOneBuyerWinsTheSameSeat() throws Exception {
        CreatedEvent event = EventFixtures.createEvent(eventService, 1);
        Long seatId = event.seatIds().getFirst();

        RaceResult result = race(buyer -> () -> holdService.create(event.id(), "buyer-" + buyer, List.of(seatId)));

        assertThat(result.unexpected()).isEmpty();
        assertThat(result.winners()).hasSize(1);
        assertThat(result.conflicts()).isEqualTo(BUYERS - 1);
        assertThat(seatRepository.findById(seatId).orElseThrow().getHoldId()).isEqualTo(result.winners().getFirst().id());
    }

    @Test
    void overlappingSeatsInOppositeOrderDoNotDeadlock() throws Exception {
        CreatedEvent event = EventFixtures.createEvent(eventService, 2);
        Long first = event.seatIds().get(0);
        Long second = event.seatIds().get(1);

        RaceResult result = race(buyer -> () -> holdService.create(event.id(), "buyer-" + buyer,
                buyer % 2 == 0 ? List.of(first, second) : List.of(second, first)));

        assertThat(result.unexpected()).as("deadlocks or other failures").isEmpty();
        assertThat(result.winners()).hasSize(1);
        assertThat(result.winners().getFirst().seats()).hasSize(2);
        assertThat(result.conflicts()).isEqualTo(BUYERS - 1);
    }

    @Test
    void takenSeatIsRejectedAndFreeSeatOfSameRequestStaysFree() {
        CreatedEvent event = EventFixtures.createEvent(eventService, 2);
        holdService.create(event.id(), "alice", List.of(event.seatIds().get(0)));

        assertThatExceptionOfType(SeatsUnavailableException.class)
                .isThrownBy(() -> holdService.create(event.id(), "bob", event.seatIds()));

        holdService.create(event.id(), "carol", List.of(event.seatIds().get(1)));
    }

    @Test
    void expiredHoldCanBeTakenOver() {
        CreatedEvent event = EventFixtures.createEvent(eventService, 1);
        holdService.create(event.id(), "alice", event.seatIds());

        clock.advance(Duration.ofMinutes(11));

        HoldResponse bobHold = holdService.create(event.id(), "bob", event.seatIds());
        assertThat(seatRepository.findById(event.seatIds().getFirst()).orElseThrow().getHoldId()).isEqualTo(bobHold.id());
    }

    @Test
    void releasedSeatCanBeHeldAgainImmediately() {
        CreatedEvent event = EventFixtures.createEvent(eventService, 1);
        HoldResponse aliceHold = holdService.create(event.id(), "alice", event.seatIds());

        holdService.release(aliceHold.id(), "alice");

        holdService.create(event.id(), "bob", event.seatIds());
    }

    RaceResult race(IntFunction<Callable<HoldResponse>> attemptForBuyer) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<HoldResponse>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(BUYERS)) {
            for (int buyer = 0; buyer < BUYERS; buyer++) {
                Callable<HoldResponse> attempt = attemptForBuyer.apply(buyer);
                attempts.add(executor.submit(() -> {
                    startGate.await();
                    return attempt.call();
                }));
            }
            startGate.countDown();

            List<HoldResponse> winners = new ArrayList<>();
            List<Throwable> unexpected = new ArrayList<>();
            int conflicts = 0;
            for (Future<HoldResponse> attempt : attempts) {
                try {
                    winners.add(attempt.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof SeatsUnavailableException) {
                        conflicts++;
                    } else {
                        unexpected.add(failure.getCause());
                    }
                }
            }
            return new RaceResult(winners, conflicts, unexpected);
        }
    }

    record RaceResult(List<HoldResponse> winners, int conflicts, List<Throwable> unexpected) {
    }
}
