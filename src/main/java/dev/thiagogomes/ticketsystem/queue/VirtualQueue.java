package dev.thiagogomes.ticketsystem.queue;

import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.shared.RedisCircuit;
import dev.thiagogomes.ticketsystem.shared.error.BusinessRuleException;
import dev.thiagogomes.ticketsystem.shared.error.ForbiddenException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Waiting room per event (admission control). Instead of thousands of buyers hitting the seat inventory at the
 * same second, they line up in Redis and are let in at a fixed rate, first come first served. Admitted buyers get
 * a signed pass; with the queue on, holding seats requires it. The database sees a steady flow, and whoever is let
 * in gets a fast answer instead of waiting behind everybody else on the connection pool.
 */
@Service
public class VirtualQueue {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> STATUS = RedisScript.of(new ClassPathResource("redis/queue-status.lua"), List.class);
    private static final int WAITING = 1;
    private static final int ADMITTED = 2;

    private final boolean enabled;
    private final int admissionsPerSecond;
    private final Duration passTtl;
    private final QueuePasses passes;
    private final StringRedisTemplate redis;
    private final RedisCircuit redisCircuit;
    private final EventService eventService;
    private final Clock clock;
    private final Counter admissions;

    public VirtualQueue(@Value("${ticket.queue.enabled}") boolean enabled,
                        @Value("${ticket.queue.admissions-per-second}") int admissionsPerSecond,
                        @Value("${ticket.queue.pass-ttl}") Duration passTtl,
                        @Value("${ticket.queue.pass-secret}") String passSecret,
                        StringRedisTemplate redis, RedisCircuit redisCircuit, EventService eventService,
                        Clock clock, MeterRegistry meters) {
        if (admissionsPerSecond < 1) {
            throw new IllegalStateException("ticket.queue.admissions-per-second must be at least 1");
        }
        this.enabled = enabled;
        this.admissionsPerSecond = admissionsPerSecond;
        this.passTtl = passTtl;
        // Fails at startup without a strong secret, but only when the queue is on
        this.passes = enabled ? new QueuePasses(passSecret) : null;
        this.redis = redis;
        this.redisCircuit = redisCircuit;
        this.eventService = eventService;
        this.clock = clock;
        this.admissions = meters.counter("queue.admissions");
    }

    /**
     * Idempotent: joining again keeps the original place in line.
     */
    public QueueStatusResponse join(long eventId, String userId) {
        if (!enabled) {
            return QueueStatusResponse.open();
        }
        if (!eventService.get(eventId).startsAt().isAfter(clock.instant())) {
            throw new BusinessRuleException("Sales are closed: the event has already started");
        }
        return status(eventId, userId, true);
    }

    public QueueStatusResponse status(long eventId, String userId) {
        if (!enabled) {
            return QueueStatusResponse.open();
        }
        return status(eventId, userId, false);
    }

    /**
     * Gate in front of seat holds. No-op while the queue is off.
     */
    public void requireAdmission(long eventId, String userId, String pass) {
        if (enabled && !passes.isValid(pass, eventId, userId, clock.instant())) {
            throw new ForbiddenException("A valid X-Queue-Pass for this event is required: join the queue with "
                    + "POST /events/%d/queue and wait to be admitted".formatted(eventId));
        }
    }

    private QueueStatusResponse status(long eventId, String userId, boolean join) {
        String prefix = "queue:{" + eventId + "}:"; // hash tag: one Redis Cluster slot per event's queue
        // Fails closed: without Redis nobody new is admitted (buyers already holding a pass keep buying, since
        // the pass is verified without Redis); letting everyone in would bring back the stampede
        @SuppressWarnings("unchecked")
        List<Long> result = redisCircuit.callOrUnavailable("The virtual queue", () -> redis.execute(STATUS,
                List.of(prefix + "waiting", prefix + "admitted", prefix + "state"),
                userId, join ? "1" : "0", String.valueOf(admissionsPerSecond), String.valueOf(passTtl.toMillis())));
        admissions.increment(result.get(2));
        return switch (result.getFirst().intValue()) {
            case ADMITTED -> {
                Instant expiresAt = Instant.ofEpochMilli(result.get(1));
                yield QueueStatusResponse.admitted(passes.issue(eventId, userId, expiresAt), expiresAt);
            }
            case WAITING -> {
                long position = result.get(1);
                yield QueueStatusResponse.waiting(position, Math.ceilDiv(position, admissionsPerSecond));
            }
            default -> throw new NotFoundException("You are not in the queue of event %d; join with POST".formatted(eventId));
        };
    }
}
