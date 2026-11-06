package dev.thiagogomes.ticketsystem.event;

import dev.thiagogomes.ticketsystem.MutableClock;
import dev.thiagogomes.ticketsystem.event.SeatMapResponse.SeatResponse;
import dev.thiagogomes.ticketsystem.event.SeatMapResponse.SectorSeats;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatMapCacheTest {

    private static final long EVENT = 1L;
    private static final Duration TTL = Duration.ofMinutes(10);

    private final EventService eventService = mock(EventService.class);
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SeatMapCache cache = new SeatMapCache(eventService, clock, TTL, meters);

    @Test
    void concurrentMissesShareOneDatabaseLoad() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(eventService.seatMap(EVENT)).thenAnswer(invocation -> {
            release.await(); // keep the load in flight while everyone else arrives
            return map(SeatStatus.AVAILABLE);
        });

        List<CompletableFuture<SeatMapSnapshot>> readers = IntStream.range(0, 50)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> cache.get(EVENT)))
                .toList();
        Thread.sleep(200);
        release.countDown();

        assertThat(readers).extracting(CompletableFuture::join).extracting(SeatMapSnapshot::etag).containsOnly(cache.get(EVENT).etag());
        verify(eventService, times(1)).seatMap(EVENT);
        assertThat(meters.counter("seatmap.loads").count()).isEqualTo(1);
    }

    @Test
    void snapshotIsReusedWithinTheTtlAndReloadedAfterIt() {
        when(eventService.seatMap(EVENT)).thenReturn(map(SeatStatus.AVAILABLE), map(SeatStatus.HELD));

        SeatMapSnapshot first = cache.get(EVENT);
        assertThat(cache.get(EVENT)).isSameAs(first);

        clock.advance(TTL);
        SeatMapSnapshot reloaded = cache.get(EVENT);

        assertThat(reloaded.etag()).isNotEqualTo(first.etag());
        verify(eventService, times(2)).seatMap(EVENT);
    }

    @Test
    void clockGoingBackDoesNotKeepAnOldSnapshotAlive() {
        when(eventService.seatMap(EVENT)).thenReturn(map(SeatStatus.AVAILABLE));
        clock.advance(TTL.multipliedBy(3));
        cache.get(EVENT);

        clock.reset();
        cache.get(EVENT);

        verify(eventService, times(2)).seatMap(EVENT);
    }

    @Test
    void failuresAreNotCached() {
        when(eventService.seatMap(EVENT)).thenThrow(new NotFoundException("Event 1 not found")).thenReturn(map(SeatStatus.SOLD));

        assertThatThrownBy(() -> cache.get(EVENT)).isInstanceOf(NotFoundException.class);
        assertThat(cache.get(EVENT).map().sectors().getFirst().seats().getFirst().status()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void idleEntriesAreEvicted() {
        when(eventService.seatMap(EVENT)).thenReturn(map(SeatStatus.AVAILABLE));
        SeatMapSnapshot first = cache.get(EVENT);

        clock.advance(Duration.ofMinutes(2));
        cache.evictIdle();
        clock.reset();

        assertThat(cache.get(EVENT)).isNotSameAs(first);
    }

    @Test
    void etagDependsOnSeatStatesOnly() {
        when(eventService.seatMap(EVENT)).thenReturn(map(SeatStatus.AVAILABLE));
        SeatMapCache otherReplica = new SeatMapCache(eventService, clock, TTL, new SimpleMeterRegistry());

        assertThat(cache.get(EVENT).etag()).isEqualTo(otherReplica.get(EVENT).etag()).hasSize(32);
    }

    private static SeatMapResponse map(SeatStatus status) {
        return new SeatMapResponse(EVENT, List.of(new SectorSeats(10L, "Pista", new BigDecimal("100.00"),
                status == SeatStatus.AVAILABLE ? 1 : 0, List.of(new SeatResponse(100L, "A", 1, status)))));
    }
}
