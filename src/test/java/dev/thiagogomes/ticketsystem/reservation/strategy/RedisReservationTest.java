package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.checkout.CheckoutService;
import dev.thiagogomes.ticketsystem.checkout.OrderService;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@IntegrationTest
@TestPropertySource(properties = "ticket.reservation.strategy=redis")
class RedisReservationTest extends ReservationStrategyContract {

    @Autowired
    StringRedisTemplate redis;
    @Autowired
    CheckoutService checkoutService;
    @Autowired
    OrderService orderService;

    @Override
    Class<? extends SeatReservationStrategy> expectedStrategy() {
        return RedisSeatReservation.class;
    }

    @Test
    void holdWritesSeatKeyWithTtl() {
        CreatedEvent event = EventFixtures.createEvent(eventService, 1);
        HoldResponse hold = holdService.create(event.id(), "alice", event.seatIds());

        String key = key(event);
        assertThat(redis.opsForValue().get(key)).startsWith(hold.id() + ":");
        assertThat(redis.getExpire(key)).isBetween(590L, 600L);
    }

    @Test
    void databaseStaysSourceOfTruthWhenRedisLosesData() {
        CreatedEvent event = EventFixtures.createEvent(eventService, 1);
        HoldResponse aliceHold = holdService.create(event.id(), "alice", event.seatIds());
        redis.delete(key(event));

        assertThatExceptionOfType(SeatsUnavailableException.class)
                .isThrownBy(() -> holdService.create(event.id(), "bob", event.seatIds()));

        assertThat(redis.hasKey(key(event))).as("bob's Redis claim compensated after rollback").isFalse();
        assertThat(seatRepository.findById(event.seatIds().getFirst()).orElseThrow().getHoldId()).isEqualTo(aliceHold.id());
    }

    @Test
    void knownTakenSeatIsRejectedBeforeTouchingTheDatabase() {
        long eventThatDoesNotExist = 987_654L;
        long expiresAt = clock.instant().plusSeconds(60).toEpochMilli();
        redis.opsForValue().set("seat:{" + eventThatDoesNotExist + "}:1", "some-hold:" + expiresAt);

        // Had the request reached the database it would answer 404 (unknown event), not 409
        assertThatExceptionOfType(SeatsUnavailableException.class)
                .isThrownBy(() -> holdService.create(eventThatDoesNotExist, "bob", List.of(1L)));
    }

    @Test
    void seatsOfACancelledOrderAreFreedInRedisToo() {
        CreatedEvent event = EventFixtures.createEvent(eventService, 1);
        HoldResponse aliceHold = holdService.create(event.id(), "alice", event.seatIds());
        UUID orderId = checkoutService.checkout(aliceHold.id(), "alice", "redis-cancel-" + UUID.randomUUID()).order().id();

        orderService.cancel(orderId, "alice");

        assertThat(redis.hasKey(key(event))).isFalse();
        holdService.create(event.id(), "bob", event.seatIds());
    }

    private static String key(CreatedEvent event) {
        return "seat:{" + event.id() + "}:" + event.seatIds().getFirst();
    }
}
