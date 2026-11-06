package dev.thiagogomes.ticketsystem.reservation;

import com.jayway.jsonpath.JsonPath;
import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.MutableClock;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class HoldControllerTest {

    private static final Duration PAST_TTL = Duration.ofMinutes(10).plusSeconds(1);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EventService eventService;
    @Autowired
    HoldService holdService;
    @Autowired
    SeatHoldRepository holdRepository;
    @Autowired
    SeatRepository seatRepository;
    @Autowired
    MutableClock clock;

    CreatedEvent event;

    @BeforeEach
    void createEvent() {
        event = EventFixtures.createEvent(eventService, 6);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void holdsSeatsAndShowsThemAsHeldInSeatMap() throws Exception {
        hold("alice", seat(0), seat(1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.total").value(200.00));

        mockMvc.perform(get("/events/{id}/seats", event.id()))
                .andExpect(jsonPath("$.sectors[0].available").value(4))
                .andExpect(jsonPath("$.sectors[0].seats[0].status").value("HELD"))
                .andExpect(jsonPath("$.sectors[0].seats[1].status").value("HELD"));
    }

    @Test
    void createdHoldIsReadableByItsOwnerOnly() throws Exception {
        String location = hold("alice", seat(0), seat(1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location).header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seats", hasSize(2)));
        mockMvc.perform(get(location).header("X-User-Id", "bob"))
                .andExpect(status().isNotFound());
    }

    @Test
    void takenSeatReturnsConflictListingUnavailableSeats() throws Exception {
        hold("alice", seat(0)).andExpect(status().isCreated());

        hold("bob", seat(0), seat(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableSeatIds", contains(seat(0).intValue())));

        mockMvc.perform(get("/events/{id}/seats", event.id()))
                .andExpect(jsonPath("$.sectors[0].seats[1].status").value("AVAILABLE"));
    }

    @Test
    void rejectsMoreThanFourSeats() throws Exception {
        hold("alice", seat(0), seat(1), seat(2), seat(3), seat(4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.seatIds").exists());
    }

    @Test
    void rejectsRepeatedSeats() throws Exception {
        hold("alice", seat(0), seat(0)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void rejectsSeatFromAnotherEvent() throws Exception {
        CreatedEvent other = EventFixtures.createEvent(eventService, 1);

        hold("alice", other.seatIds().getFirst()).andExpect(status().isNotFound());
    }

    @Test
    void userCannotHaveTwoActiveHoldsForTheSameEvent() throws Exception {
        String firstHold = holdId(hold("alice", seat(0)));

        hold("alice", seat(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.holdId").value(firstHold));
    }

    @Test
    void requiresUserHeader() throws Exception {
        mockMvc.perform(post("/events/{id}/holds", event.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seatsJson(seat(0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedUserId() throws Exception {
        hold("<script>alert(1)</script>", seat(0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.userId").exists());
        hold("a".repeat(65), seat(0)).andExpect(status().isBadRequest());
    }

    @Test
    void salesCloseWhenTheEventStarts() throws Exception {
        clock.advance(Duration.ofDays(31)); // fixture events start 30 days from now

        hold("alice", seat(0))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Sales are closed: the event has already started"));
    }

    @Test
    void releasingHoldFreesSeats() throws Exception {
        String holdId = holdId(hold("alice", seat(0)));

        mockMvc.perform(delete("/holds/{id}", holdId).header("X-User-Id", "bob"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/holds/{id}", holdId).header("X-User-Id", "alice"))
                .andExpect(status().isNoContent());

        assertThat(holdRepository.findById(UUID.fromString(holdId)).orElseThrow().getStatus()).isEqualTo(HoldStatus.RELEASED);
        hold("bob", seat(0)).andExpect(status().isCreated());
    }

    @Test
    void expiredHoldFreesSeatsImmediatelyWithoutWaitingForCleanup() throws Exception {
        hold("alice", seat(0)).andExpect(status().isCreated());

        clock.advance(PAST_TTL);

        mockMvc.perform(get("/events/{id}/seats", event.id()))
                .andExpect(jsonPath("$.sectors[0].seats[0].status").value("AVAILABLE"));
        hold("bob", seat(0)).andExpect(status().isCreated());
        hold("alice", seat(1)).andExpect(status().isCreated());
    }

    @Test
    void cleanupMarksExpiredHoldsAndClearsSeats() throws Exception {
        UUID holdId = UUID.fromString(holdId(hold("alice", seat(0))));

        clock.advance(PAST_TTL);
        holdService.releaseExpired();

        assertThat(holdRepository.findById(holdId).orElseThrow().getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(seatRepository.findById(seat(0)).orElseThrow().getHoldId()).isNull();
    }

    private Long seat(int index) {
        return event.seatIds().get(index);
    }

    private ResultActions hold(String userId, Long... seatIds) throws Exception {
        return mockMvc.perform(post("/events/{id}/holds", event.id())
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(seatsJson(seatIds)));
    }

    private static String seatsJson(Long... seatIds) {
        String ids = List.of(seatIds).stream().map(String::valueOf).collect(Collectors.joining(","));
        return "{\"seatIds\":[" + ids + "]}";
    }

    private static String holdId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
