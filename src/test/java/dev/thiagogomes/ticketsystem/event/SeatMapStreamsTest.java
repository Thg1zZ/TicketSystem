package dev.thiagogomes.ticketsystem.event;

import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import dev.thiagogomes.ticketsystem.shared.error.ServiceUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeatMapStreamsTest {

    private final SeatMapCache cache = mock(SeatMapCache.class);
    private final SeatMapStreams streams = new SeatMapStreams(cache, JsonMapper.builder().build(), 1, Duration.ofMinutes(1),
            new SimpleMeterRegistry());

    @Test
    void refusesStreamsAboveTheLimitSoClientsFallBackToPolling() {
        when(cache.get(1L)).thenReturn(new SeatMapSnapshot(new SeatMapResponse(1L, List.of()), "v1"));
        streams.subscribe(1L);

        assertThatThrownBy(() -> streams.subscribe(1L)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(streams.openStreams()).isEqualTo(1);
    }

    @Test
    void unknownEventIsRejectedBeforeOpeningAStream() {
        when(cache.get(2L)).thenThrow(new NotFoundException("Event 2 not found"));

        assertThatThrownBy(() -> streams.subscribe(2L)).isInstanceOf(NotFoundException.class);
        assertThat(streams.openStreams()).isZero();
    }
}
