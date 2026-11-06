package dev.thiagogomes.ticketsystem;

import dev.thiagogomes.ticketsystem.checkout.CheckoutService;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@IntegrationTest
class BusinessMetricsTest {

    @Autowired
    EventService eventService;
    @Autowired
    HoldService holdService;
    @Autowired
    CheckoutService checkoutService;
    @Autowired
    MeterRegistry meters;

    @Test
    void salesFlowIsCountedByOutcome() {
        CreatedEvent event = EventFixtures.createEvent(eventService, 2);
        double created = count("holds", "created");
        double taken = count("holds", "seat_taken");
        double confirmed = count("checkout", "confirmed");
        double replayed = count("checkout", "replayed");
        double issued = issuedTickets();

        HoldResponse hold = holdService.create(event.id(), "metrics-alice", event.seatIds());
        assertThatThrownBy(() -> holdService.create(event.id(), "metrics-bob", List.of(event.seatIds().getFirst())))
                .isInstanceOf(SeatsUnavailableException.class);
        checkoutService.checkout(hold.id(), "metrics-alice", "metrics-key");
        checkoutService.checkout(hold.id(), "metrics-alice", "metrics-key");

        assertThat(count("holds", "created")).isEqualTo(created + 1);
        assertThat(count("holds", "seat_taken")).isEqualTo(taken + 1);
        assertThat(count("checkout", "confirmed")).isEqualTo(confirmed + 1);
        assertThat(count("checkout", "replayed")).isEqualTo(replayed + 1);
        await().atMost(Duration.ofSeconds(15)).until(() -> issuedTickets() >= issued + 2);
        await().atMost(Duration.ofSeconds(15)).until(() -> meters.get("outbox.pending").gauge().value() == 0);
    }

    private double count(String name, String outcome) {
        return meters.counter(name, "outcome", outcome).count();
    }

    private double issuedTickets() {
        return meters.counter("tickets.issued").count();
    }
}
