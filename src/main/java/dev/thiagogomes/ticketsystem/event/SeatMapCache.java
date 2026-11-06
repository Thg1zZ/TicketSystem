package dev.thiagogomes.ticketsystem.event;

import dev.thiagogomes.ticketsystem.event.SeatMapResponse.SeatResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Seat maps are read far more than they change. Every reader of an event within the TTL gets the same snapshot,
 * and concurrent misses share one database load (single-flight), so a crowd refreshing the map costs one query
 * per TTL per instance instead of one per request.
 */
@Component
public class SeatMapCache {

    private static final Duration IDLE_EVICTION = Duration.ofMinutes(1);

    private final ConcurrentMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final EventService eventService;
    private final Clock clock;
    private final Duration ttl;
    private final Counter loads;

    public SeatMapCache(EventService eventService, Clock clock,
                        @Value("${ticket.seat-map.cache-ttl}") Duration ttl, MeterRegistry meters) {
        this.eventService = eventService;
        this.clock = clock;
        this.ttl = ttl;
        this.loads = meters.counter("seatmap.loads");
    }

    public SeatMapSnapshot get(Long eventId) {
        Instant now = clock.instant();
        Entry entry = entries.compute(eventId, (id, current) ->
                current != null && current.isUsableAt(now, ttl) ? current : new Entry(now, new FutureTask<>(() -> load(id))));
        entry.load().run(); // no-op unless this caller created the entry: the others wait for the same result
        try {
            return entry.load().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading the seat map", e);
        } catch (ExecutionException e) {
            entries.remove(eventId, entry); // never cache a failure (unknown event, database down)
            throw e.getCause() instanceof RuntimeException cause ? cause : new IllegalStateException(e.getCause());
        }
    }

    /**
     * Keeps memory proportional to the events being watched right now.
     */
    public void evictIdle() {
        Instant cutoff = clock.instant().minus(IDLE_EVICTION);
        entries.values().removeIf(entry -> entry.startedAt().isBefore(cutoff));
    }

    private SeatMapSnapshot load(Long eventId) {
        loads.increment();
        SeatMapResponse map = eventService.seatMap(eventId);
        return new SeatMapSnapshot(map, etagOf(map));
    }

    private static String etagOf(SeatMapResponse map) {
        MessageDigest digest = sha256();
        ByteBuffer seat = ByteBuffer.allocate(Long.BYTES + 1);
        map.sectors().forEach(sector -> sector.seats().forEach((SeatResponse s) ->
                digest.update(seat.clear().putLong(s.id()).put((byte) s.status().ordinal()).flip())));
        return HexFormat.of().formatHex(digest.digest(), 0, 16);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private record Entry(Instant startedAt, FutureTask<SeatMapSnapshot> load) {

        /**
         * An in-flight load is always shared; a finished one only within the TTL (and never if the clock went back).
         */
        boolean isUsableAt(Instant now, Duration ttl) {
            return !load.isDone() || (!now.isBefore(startedAt) && startedAt.plus(ttl).isAfter(now));
        }
    }
}
