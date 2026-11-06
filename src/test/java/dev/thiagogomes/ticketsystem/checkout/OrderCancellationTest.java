package dev.thiagogomes.ticketsystem.checkout;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.MutableClock;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import dev.thiagogomes.ticketsystem.ticketing.Ticket;
import dev.thiagogomes.ticketsystem.ticketing.TicketRepository;
import dev.thiagogomes.ticketsystem.ticketing.TicketStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class OrderCancellationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EventService eventService;
    @Autowired
    HoldService holdService;
    @Autowired
    CheckoutService checkoutService;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    MeterRegistry meters;
    @Autowired
    MutableClock clock;

    CreatedEvent event;
    UUID orderId;

    @BeforeEach
    void buyBothSeats() {
        event = EventFixtures.createEvent(eventService, 2);
        HoldResponse hold = holdService.create(event.id(), "alice", event.seatIds());
        orderId = checkoutService.checkout(hold.id(), "alice", "cancel-" + UUID.randomUUID()).order().id();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void cancellingFreesSeatsVoidsTicketsAndRefunds() throws Exception {
        List<Ticket> tickets = await().atMost(Duration.ofSeconds(15))
                .until(() -> ticketRepository.findByOrderIdOrderBySeatId(orderId), issued -> issued.size() == 2);
        double refundsBefore = refunds();

        cancel("alice").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(ticketRepository.findByOrderIdOrderBySeatId(orderId)).extracting(Ticket::getStatus)
                .containsOnly(TicketStatus.CANCELLED);
        mockMvc.perform(get("/tickets/{id}/qr", tickets.getFirst().getId()).header("X-User-Id", "alice"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/events/{id}/seats", event.id()))
                .andExpect(jsonPath("$.sectors[0].available").value(2));
        assertThat(refunds()).isEqualTo(refundsBefore + 1);
    }

    @Test
    void cancelledSeatsCanBeSoldAgain() throws Exception {
        cancel("alice").andExpect(status().isOk());

        HoldResponse resale = holdService.create(event.id(), "bob", event.seatIds());
        assertThat(checkoutService.checkout(resale.id(), "bob", "resale-" + UUID.randomUUID()).order().status())
                .isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void cancellingTwiceIsIdempotentAndRefundsOnce() throws Exception {
        double refundsBefore = refunds();

        cancel("alice").andExpect(status().isOk());
        cancel("alice").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(refunds()).isEqualTo(refundsBefore + 1);
    }

    @Test
    void onlyTheOwnerCanCancel() throws Exception {
        cancel("mallory").andExpect(status().isNotFound());
    }

    @Test
    void cannotCancelAfterTheEventStarts() throws Exception {
        clock.advance(Duration.ofDays(31));

        cancel("alice").andExpect(status().isUnprocessableContent());
    }

    private ResultActions cancel(String userId) throws Exception {
        return mockMvc.perform(post("/orders/{id}/cancel", orderId).header("X-User-Id", userId));
    }

    private double refunds() {
        var counter = meters.find("payments").tag("outcome", "refunded").counter();
        return counter == null ? 0 : counter.count();
    }
}
