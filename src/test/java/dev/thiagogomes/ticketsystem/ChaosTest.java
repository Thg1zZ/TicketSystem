package dev.thiagogomes.ticketsystem;

import com.jayway.jsonpath.JsonPath;
import dev.thiagogomes.ticketsystem.checkout.CheckoutService;
import dev.thiagogomes.ticketsystem.checkout.OrderStatus;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import dev.thiagogomes.ticketsystem.ticketing.TicketRepository;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.Toxic;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static dev.thiagogomes.ticketsystem.ChaosTestConfiguration.POSTGRES_PROXY;
import static dev.thiagogomes.ticketsystem.ChaosTestConfiguration.RABBIT_PROXY;
import static dev.thiagogomes.ticketsystem.ChaosTestConfiguration.REDIS_PROXY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dependencies fail in the middle of the flow (Toxiproxy cuts or slows the connection) and the system must degrade
 * without ever selling a seat twice or losing a sale.
 */
@SpringBootTest(properties = {
        "ticket.reservation.strategy=redis",
        "ticket.payment.min-latency=0s",
        "ticket.payment.max-latency=0s",
        "ticket.payment.failure-rate=0",
        "ticket.admin.token=chaos-admin-token",
        "ticket.outbox.relay-interval=PT0.1S",
        "ticket.seat-map.cache-ttl=0s",
        "ticket.queue.enabled=false",
        // On, to prove it fails open; limits high enough for the test itself
        "ticket.rate-limit.enabled=true",
        "ticket.rate-limit.per-user.capacity=100",
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "resilience4j.circuitbreaker.instances.redis.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.redis.wait-duration-in-open-state=2s"
})
@AutoConfigureMockMvc
@Import(ChaosTestConfiguration.class)
class ChaosTest {

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
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry meters;

    @AfterEach
    void healTheNetwork() throws IOException {
        for (Proxy proxy : new Proxy[]{POSTGRES_PROXY, REDIS_PROXY, RABBIT_PROXY}) {
            proxy.enable();
            for (Toxic toxic : proxy.toxics().getAll()) {
                toxic.remove();
            }
        }
    }

    @Test
    void redisDownHoldsGoOnThroughTheDatabaseWithoutDoubleSales() throws Exception {
        CreatedEvent event = EventFixtures.createEvent(eventService, 2);
        long seat = event.seatIds().getFirst();
        REDIS_PROXY.disable();

        String holdId = idOf(hold(event.id(), "chaos-ana", seat).andExpect(status().isCreated()));
        hold(event.id(), "chaos-bia", seat).andExpect(status().isConflict()); // the database is still the gate
        mockMvc.perform(delete("/holds/{id}", holdId).header("X-User-Id", "chaos-ana")).andExpect(status().isNoContent());
        hold(event.id(), "chaos-bia", seat).andExpect(status().isCreated());

        mockMvc.perform(get("/readyz")).andExpect(status().isOk()); // degraded, not out of the load balancer
        assertThat(meters.counter("redis.fallback", "operation", "seat-claim").count()).isPositive();
        assertThat(meters.counter("redis.fallback", "operation", "rate-limit").count()).isPositive(); // failed open
    }

    @Test
    void stuckRedisCostsAtMostOneTimeoutBeforeTheCircuitOpens() throws Exception {
        CreatedEvent event = EventFixtures.createEvent(eventService, 3);
        REDIS_PROXY.toxics().latency("stuck", ToxicDirection.DOWNSTREAM, 5_000);

        hold(event.id(), "slow-1", event.seatIds().get(0)).andExpect(status().isCreated()); // pays the timeouts

        long start = System.nanoTime();
        hold(event.id(), "slow-2", event.seatIds().get(1)).andExpect(status().isCreated());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500)); // circuit open
    }

    @Test
    void rabbitDownOrdersStillConfirmAndTicketsArriveWhenItReturns() throws Exception {
        CreatedEvent event = EventFixtures.createEvent(eventService, 2);
        HoldResponse hold = holdService.create(event.id(), "chaos-rabbit", event.seatIds());
        RABBIT_PROXY.disable();

        UUID orderId = checkoutService.checkout(hold.id(), "chaos-rabbit", "chaos-rabbit-key").order().id();
        assertThat(checkoutService.checkout(hold.id(), "chaos-rabbit", "chaos-rabbit-key").order().status())
                .isEqualTo(OrderStatus.CONFIRMED);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .until(() -> ticketRepository.findByOrderIdOrderBySeatId(orderId).isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND published_at IS NULL",
                Long.class, orderId)).isEqualTo(1); // kept in the outbox, not lost

        RABBIT_PROXY.enable();
        await().atMost(Duration.ofSeconds(60))
                .until(() -> ticketRepository.findByOrderIdOrderBySeatId(orderId).size() == 2);
    }

    @Test
    void postgresDownAnswers503WithRetryAfterAndRecovers() throws Exception {
        CreatedEvent event = EventFixtures.createEvent(eventService, 1);
        POSTGRES_PROXY.disable();

        mockMvc.perform(get("/events/{id}", event.id()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        mockMvc.perform(get("/readyz")).andExpect(status().isServiceUnavailable());

        POSTGRES_PROXY.enable();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mockMvc.perform(get("/events/{id}", event.id())).andExpect(status().isOk()));
    }

    private ResultActions hold(long eventId, String userId, long seatId) throws Exception {
        return mockMvc.perform(post("/events/{id}/holds", eventId).header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[%d]}".formatted(seatId)));
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
