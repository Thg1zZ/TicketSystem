package dev.thiagogomes.ticketsystem.reservation;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

/**
 * Many buyers released at the same instant against the same seat(s), on a real Postgres.
 */
@IntegrationTest
class SeatReservationConcurrencyTest {

    private static final int BUYERS = 50;

    @Autowired
    HoldService holdService;
    @Autowired
    EventService eventService;
    @Autowired
    SeatRepository seatRepository;

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
        assertThat(result.conflicts()).isEqualTo(BUYERS - 1);
    }

    private RaceResult race(IntFunction<Callable<HoldResponse>> attemptForBuyer) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<HoldResponse>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(BUYERS)) {
            for (int buyer = 0; buyer < BUYERS; buyer++) {
                var attempt = attemptForBuyer.apply(buyer);
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

    private record RaceResult(List<HoldResponse> winners, int conflicts, List<Throwable> unexpected) {
    }
}
