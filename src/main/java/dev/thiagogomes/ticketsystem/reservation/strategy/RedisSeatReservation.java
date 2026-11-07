package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.Seat;
import dev.thiagogomes.ticketsystem.reservation.SeatHold;
import dev.thiagogomes.ticketsystem.reservation.SeatInventoryRepository;
import dev.thiagogomes.ticketsystem.reservation.SeatsUnavailableException;
import dev.thiagogomes.ticketsystem.shared.RedisCircuit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Redis is the gate, Postgres stays the source of truth. A Lua script claims all seats or none,
 * so only winners reach the database, which then confirms with a conditional UPDATE
 * (protects against Redis data loss or a seat sold after its Redis key expired).
 * <p>
 * If Redis is down the strategy degrades to that database gate alone: slower in a rush, never wrong.
 */
class RedisSeatReservation implements SeatReservationStrategy {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM = RedisScript.of(new ClassPathResource("redis/claim-seats.lua"), List.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(new ClassPathResource("redis/release-seats.lua"), Long.class);

    private final SeatInventoryRepository seatInventory;
    private final StringRedisTemplate redis;
    private final RedisCircuit redisCircuit;

    RedisSeatReservation(SeatInventoryRepository seatInventory, StringRedisTemplate redis, RedisCircuit redisCircuit) {
        this.seatInventory = seatInventory;
        this.redis = redis;
        this.redisCircuit = redisCircuit;
    }

    @Override
    public List<Seat> reserve(Long eventId, List<Long> seatIds, SeatHold hold, Instant now) {
        List<Seat> seats = seatInventory.findForEvent(eventId, seatIds);
        SeatClaims.ensureAllFound(eventId, seatIds, seats);
        List<Long> orderedIds = SeatClaims.ids(seats);
        List<String> keys = keys(eventId, orderedIds);

        // Registered before claiming: a claim that reached Redis but whose answer was lost (timeout) is undone too
        releaseIfTransactionFails(keys, hold.getId());
        List<Long> takenPositions = redisCircuit.callOrElse("seat-claim", () -> claim(keys, hold, now), () -> null);
        if (takenPositions != null && !takenPositions.isEmpty()) {
            throw new SeatsUnavailableException(takenPositions.stream().map(pos -> orderedIds.get(pos.intValue() - 1)).toList());
        }

        int claimed = seatInventory.claimIfAvailable(orderedIds, hold.getId(), hold.getExpiresAt(), now);
        if (claimed != orderedIds.size()) {
            throw new SeatsUnavailableException(orderedIds);
        }
        return seats;
    }

    /**
     * One MGET, no lock, no database: during a rush this answers most losers. Not atomic, hence only a filter;
     * the Lua claim inside {@link #reserve} is still the real gate.
     */
    @Override
    public void rejectIfKnownTaken(Long eventId, List<Long> seatIds, Instant now) {
        List<String> claims = redisCircuit.callOrElse("seat-filter",
                () -> redis.opsForValue().multiGet(keys(eventId, seatIds)), () -> null);
        if (claims == null) {
            return; // no filter without Redis: the database still decides
        }
        List<Long> taken = new ArrayList<>();
        for (int i = 0; i < seatIds.size(); i++) {
            String claim = claims.get(i);
            if (claim != null && claimExpiresAt(claim) > now.toEpochMilli()) {
                taken.add(seatIds.get(i));
            }
        }
        if (!taken.isEmpty()) {
            throw new SeatsUnavailableException(taken);
        }
    }

    @Override
    public void release(SeatHold hold, List<Long> seatIds) {
        if (!seatIds.isEmpty()) {
            releaseClaims(keys(hold.getEventId(), seatIds), hold.getId());
        }
    }

    /**
     * Without Redis the claims cannot be removed now; they expire with the hold deadline anyway.
     */
    private void releaseClaims(List<String> keys, UUID holdId) {
        redisCircuit.callOrElse("seat-release", () -> redis.execute(RELEASE, keys, holdId.toString()), () -> 0L);
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
                    releaseClaims(keys, holdId);
                }
            }
        });
    }

    /**
     * Same value format the Lua scripts write: "holdId:expiresAtEpochMs".
     */
    private static long claimExpiresAt(String claim) {
        return Long.parseLong(claim.substring(claim.lastIndexOf(':') + 1));
    }

    /**
     * The {eventId} hash tag keeps an event's seats in one Redis Cluster slot, which multi-key scripts require.
     */
    private static List<String> keys(Long eventId, List<Long> seatIds) {
        return seatIds.stream().map(seatId -> "seat:{" + eventId + "}:" + seatId).toList();
    }
}
