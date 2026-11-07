package dev.thiagogomes.ticketsystem.queue;

import com.jayway.jsonpath.JsonPath;
import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Queue and rate limit on, with small numbers so the behavior shows within a test.
 */
@IntegrationTest
@TestPropertySource(properties = {
        "ticket.queue.enabled=true",
        "ticket.queue.admissions-per-second=5",
        "ticket.queue.pass-secret=admission-control-test-secret-0123456789",
        "ticket.rate-limit.enabled=true",
        "ticket.rate-limit.per-ip.capacity=60",
        "ticket.rate-limit.per-ip.refill-per-second=1",
        "ticket.rate-limit.per-user.capacity=6",
        "ticket.rate-limit.per-user.refill-per-second=0.5"
})
class AdmissionControlTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EventService eventService;
    @Autowired
    StringRedisTemplate redis;

    CreatedEvent event;

    @BeforeEach
    void createEvent() {
        event = EventFixtures.createEvent(eventService, 6);
    }

    @Test
    void holdingSeatsRequiresThePassOfThisBuyerForThisEvent() throws Exception {
        String pass = admittedPass("gate-alice", event.id());
        CreatedEvent otherEvent = EventFixtures.createEvent(eventService, 2);

        hold("gate-alice", event.id(), null, event.seatIds().get(0)).andExpect(status().isForbidden());
        hold("gate-bob", event.id(), pass, event.seatIds().get(0)).andExpect(status().isForbidden());
        hold("gate-alice", otherEvent.id(), pass, otherEvent.seatIds().get(0)).andExpect(status().isForbidden());
        hold("gate-alice", event.id(), pass + "x", event.seatIds().get(0)).andExpect(status().isForbidden());

        hold("gate-alice", event.id(), pass, event.seatIds().get(0)).andExpect(status().isCreated());
    }

    @Test
    void buyersAreAdmittedInArrivalOrderAtTheConfiguredRate() throws Exception {
        List<String> buyers = IntStream.range(0, 10).mapToObj(i -> "fifo-" + i).toList();
        long start = System.nanoTime();
        for (String buyer : buyers) {
            join(buyer, event.id()).andExpect(status().isOk());
        }

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            List<Boolean> admitted = new ArrayList<>();
            for (String buyer : buyers) {
                admitted.add("ADMITTED".equals(JsonPath.read(statusOf(buyer, event.id()), "$.status")));
            }
            // FIFO: whoever is admitted, everybody who arrived earlier is admitted too
            assertThat(admitted.indexOf(false) == -1 || !admitted.subList(admitted.indexOf(false), admitted.size()).contains(true))
                    .as("admitted %s", admitted).isTrue();
            return !admitted.contains(false);
        });

        // 5 per second: the 9 buyers behind the first one need at least ~1.8 s
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofMillis(1500));
    }

    @Test
    void waitingBuyerSeesPositionAndEstimateAndJoiningAgainKeepsThePlace() throws Exception {
        for (int i = 0; i < 12; i++) {
            join("line-" + i, event.id());
        }
        String waiting = statusOf("line-11", event.id());
        assertThat((String) JsonPath.read(waiting, "$.status")).isEqualTo("WAITING");
        int position = JsonPath.read(waiting, "$.position");
        assertThat((Integer) JsonPath.read(waiting, "$.estimatedWaitSeconds")).isPositive();

        int again = JsonPath.read(join("line-11", event.id()).andReturn().getResponse().getContentAsString(), "$.position");
        assertThat(again).isLessThanOrEqualTo(position); // never sent to the back of the line
    }

    @Test
    void statusOfSomeoneOutsideTheQueueIs404AndWritesNothing() throws Exception {
        mockMvc.perform(get("/events/{id}/queue", 987_654_321).header("X-User-Id", "never-joined"))
                .andExpect(status().isNotFound());

        // An unauthenticated GET flood with made-up event ids must not fill Redis with keys
        assertThat(redis.keys("queue:{987654321}:*")).isEmpty();
    }

    @Test
    void userOverTheLimitGets429WithRetryAfter() throws Exception {
        for (int i = 0; i < 6; i++) {
            join("flooder", event.id()).andExpect(status().isOk());
        }
        join("flooder", event.id())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, org.hamcrest.Matchers.oneOf("1", "2")))
                .andExpect(jsonPath("$.status").value(429));

        join("polite-neighbor", event.id()).andExpect(status().isOk());
    }

    @Test
    void ipOverTheLimitGets429EvenWithFreshUserIds() throws Exception {
        for (int i = 0; i < 60; i++) {
            joinFrom("10.9.9.9", "bot-" + i, event.id()).andExpect(status().isOk());
        }
        joinFrom("10.9.9.9", "bot-60", event.id()).andExpect(status().isTooManyRequests());
        joinFrom("10.9.9.10", "bot-61", event.id()).andExpect(status().isOk());
    }

    private String admittedPass(String userId, long eventId) throws Exception {
        join(userId, eventId).andExpect(status().isOk());
        return await().atMost(Duration.ofSeconds(5)).until(() -> {
            String status = statusOf(userId, eventId);
            return "ADMITTED".equals(JsonPath.read(status, "$.status")) ? JsonPath.<String>read(status, "$.pass") : null;
        }, pass -> pass != null);
    }

    private String statusOf(String userId, long eventId) throws Exception {
        return mockMvc.perform(get("/events/{id}/queue", eventId).header("X-User-Id", userId))
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions join(String userId, long eventId) throws Exception {
        return joinFrom("127.0.0.1", userId, eventId);
    }

    private ResultActions joinFrom(String ip, String userId, long eventId) throws Exception {
        return mockMvc.perform(post("/events/{id}/queue", eventId).header("X-User-Id", userId)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    private ResultActions hold(String userId, long eventId, String pass, long seatId) throws Exception {
        var request = post("/events/{id}/holds", eventId).header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[%d]}".formatted(seatId));
        if (pass != null) {
            request.header("X-Queue-Pass", pass);
        }
        return mockMvc.perform(request);
    }
}
