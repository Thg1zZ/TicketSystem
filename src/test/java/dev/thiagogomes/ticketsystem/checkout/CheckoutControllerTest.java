package dev.thiagogomes.ticketsystem.checkout;

import com.jayway.jsonpath.JsonPath;
import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.MutableClock;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class CheckoutControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EventService eventService;
    @Autowired
    HoldService holdService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    MutableClock clock;

    CreatedEvent event;
    HoldResponse hold;

    @BeforeEach
    void holdTwoSeats() {
        event = EventFixtures.createEvent(eventService, 3);
        hold = holdService.create(event.id(), "alice", event.seatIds().subList(0, 2));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void confirmsOrderAndSellsSeats() throws Exception {
        checkout(hold.id(), "alice", "key-confirm-0001")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/orders/")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.total").value(200.00))
                .andExpect(jsonPath("$.paymentReference", startsWith("pay_")))
                .andExpect(jsonPath("$.items", hasSize(2)));

        mockMvc.perform(get("/events/{id}/seats", event.id()))
                .andExpect(jsonPath("$.sectors[0].seats[0].status").value("SOLD"))
                .andExpect(jsonPath("$.sectors[0].seats[1].status").value("SOLD"))
                .andExpect(jsonPath("$.sectors[0].seats[2].status").value("AVAILABLE"));
    }

    @Test
    void replayWithSameKeyReturnsTheSameOrder() throws Exception {
        String first = body(checkout(hold.id(), "alice", "key-replay-0001").andExpect(status().isCreated()));

        checkout(hold.id(), "alice", "key-replay-0001")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")))
                .andExpect(jsonPath("$.paymentReference").value(JsonPath.<String>read(first, "$.paymentReference")));

        assertThat(orderRepository.findByUserIdAndIdempotencyKey("alice", "key-replay-0001")).isPresent();
    }

    @Test
    void sameKeyForAnotherHoldIsRejected() throws Exception {
        checkout(hold.id(), "alice", "key-reused-0001").andExpect(status().isCreated());
        HoldResponse secondHold = holdService.create(event.id(), "alice", event.seatIds().subList(2, 3));

        checkout(secondHold.id(), "alice", "key-reused-0001").andExpect(status().isUnprocessableContent());
    }

    @Test
    void secondKeyForAnAlreadyPaidHoldIsConflict() throws Exception {
        checkout(hold.id(), "alice", "key-first-00001").andExpect(status().isCreated());

        checkout(hold.id(), "alice", "key-second-0001").andExpect(status().isConflict());
    }

    @Test
    void idempotencyKeyIsRequiredAndValidated() throws Exception {
        mockMvc.perform(post("/holds/{id}/checkout", hold.id()).header("X-User-Id", "alice"))
                .andExpect(status().isBadRequest());
        checkout(hold.id(), "alice", "short").andExpect(status().isBadRequest());
        checkout(hold.id(), "alice", "has spaces in it").andExpect(status().isBadRequest());
    }

    @Test
    void cannotCheckoutSomeoneElsesHold() throws Exception {
        checkout(hold.id(), "mallory", "key-mallory-001").andExpect(status().isNotFound());
    }

    @Test
    void expiredHoldCannotBeCheckedOut() throws Exception {
        clock.advance(Duration.ofMinutes(11));

        checkout(hold.id(), "alice", "key-expired-001").andExpect(status().isConflict());
        assertThat(orderRepository.findByUserIdAndIdempotencyKey("alice", "key-expired-001")).isEmpty();
    }

    @Test
    void paidHoldCannotBeReleased() throws Exception {
        checkout(hold.id(), "alice", "key-release-001").andExpect(status().isCreated());

        mockMvc.perform(delete("/holds/{id}", hold.id()).header("X-User-Id", "alice"))
                .andExpect(status().isConflict());
    }

    @Test
    void ordersAreVisibleOnlyToTheirOwner() throws Exception {
        String orderId = JsonPath.read(body(checkout(hold.id(), "alice", "key-owner-0001")), "$.id");

        mockMvc.perform(get("/orders/{id}", orderId).header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].price", everyItem(is(100.0))));
        mockMvc.perform(get("/orders/{id}", orderId).header("X-User-Id", "mallory"))
                .andExpect(status().isNotFound());
    }

    private ResultActions checkout(UUID holdId, String userId, String key) throws Exception {
        return mockMvc.perform(post("/holds/{id}/checkout", holdId)
                .header("X-User-Id", userId)
                .header("Idempotency-Key", key));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
