package dev.thiagogomes.ticketsystem.checkout;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import dev.thiagogomes.ticketsystem.shared.error.ConflictException;
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

@IntegrationTest
class CheckoutConcurrencyTest {

    private static final int REQUESTS = 10;

    @Autowired
    CheckoutService checkoutService;
    @Autowired
    HoldService holdService;
    @Autowired
    EventService eventService;

    @Test
    void concurrentRetriesWithSameKeyCreateOneOrderAndOneCharge() throws Exception {
        HoldResponse hold = newHold("double-clicker");

        List<Outcome> outcomes = race(i -> () -> checkoutService.checkout(hold.id(), "double-clicker", "same-key-123456"));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes.stream().map(o -> o.result().order().id()).distinct()).hasSize(1);
        assertThat(outcomes.stream().map(o -> o.result().order().paymentReference()).distinct()).hasSize(1);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.result().order().status()).isEqualTo(OrderStatus.CONFIRMED));
    }

    @Test
    void concurrentCheckoutsOfSameHoldWithDifferentKeysConfirmOnlyOne() throws Exception {
        HoldResponse hold = newHold("two-tabs");

        List<Outcome> outcomes = race(i -> () -> checkoutService.checkout(hold.id(), "two-tabs", "tab-key-" + i + "-abcdef"));

        assertThat(outcomes.stream().filter(o -> o.error() == null)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.error() instanceof ConflictException)).hasSize(REQUESTS - 1);
    }

    private HoldResponse newHold(String userId) {
        CreatedEvent event = EventFixtures.createEvent(eventService, 2);
        return holdService.create(event.id(), userId, event.seatIds());
    }

    private List<Outcome> race(IntFunction<Callable<CheckoutResult>> attemptFor) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<CheckoutResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(REQUESTS)) {
            for (int i = 0; i < REQUESTS; i++) {
                Callable<CheckoutResult> attempt = attemptFor.apply(i);
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return attempt.call();
                }));
            }
            startGate.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<CheckoutResult> future : futures) {
                try {
                    outcomes.add(new Outcome(future.get(60, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome(null, e.getCause()));
                }
            }
            return outcomes;
        }
    }

    private record Outcome(CheckoutResult result, Throwable error) {
    }
}
