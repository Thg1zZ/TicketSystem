package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Redis is the gate, Postgres stays the source of truth. A Lua script claims all seats or none,
 * so only winners reach the database, which then confirms with a conditional UPDATE
 * (protects against Redis data loss or a seat sold after its Redis key expired).
 */
@Component
@ConditionalOnProperty(name = SeatReservationStrategy.PROPERTY, havingValue = "redis")
class RedisSeatReservation implements SeatReservationStrategy {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM = RedisScript.of(new ClassPathResource("redis/claim-seats.lua"), List.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(new ClassPathResource("redis/release-seats.lua"), Long.class);

    private final SeatRepository seatRepository;
    private final StringRedisTemplate redis;

    RedisSeatReservation(SeatRepository seatRepository, StringRedisTemplate redis) {
        this.seatRepository = seatRepository;
        this.redis = redis;
    }

    @Override
    public List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now) {
        List<Seat> seats = seatRepository.findForEvent(eventId, seatIds);
        SeatClaims.ensureAllFound(eventId, seatIds, seats);
        List<Long> orderedIds = SeatClaims.ids(seats);
        List<String> keys = keys(eventId, orderedIds);

        List<Long> takenPositions = claim(keys, hold, now);
        if (!takenPositions.isEmpty()) {
            throw new SeatsUnavailableException(takenPositions.stream().map(pos -> orderedIds.get(pos.intValue() - 1)).toList());
        }
        releaseIfTransactionFails(keys, hold.getId());

        int claimed = seatRepository.claimIfAvailable(orderedIds, hold.getId(), hold.getExpiresAt(), now);
        if (claimed != orderedIds.size()) {
            throw new SeatsUnavailableException(orderedIds);
        }
        return seats;
    }

    @Override
    public void release(SeatHold hold, List<Long> seatIds) {
        if (!seatIds.isEmpty()) {
            redis.execute(RELEASE, keys(hold.getEventId(), seatIds), hold.getId().toString());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> claim(List<String> keys, SeatHold hold, Instant now) {
        return redis.execute(CLAIM, keys,
                hold.getId().toString(),
                String.valueOf(now.toEpochMilli()),
                String.valueOf(hold.getExpiresAt().toEpochMilli()));
    }

    /**
     * Compensation: if the database part rolls back, the Redis claim must not keep blocking the seats.
     */
    private void releaseIfTransactionFails(List<String> keys, UUID holdId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    redis.execute(RELEASE, keys, holdId.toString());
                }
            }
        });
    }

    /**
     * The {eventId} hash tag keeps an event's seats in one Redis Cluster slot, which multi-key scripts require.
     */
    private static List<String> keys(Long eventId, List<Long> seatIds) {
        return seatIds.stream().map(seatId -> "seat:{" + eventId + "}:" + seatId).toList();
    }
}
