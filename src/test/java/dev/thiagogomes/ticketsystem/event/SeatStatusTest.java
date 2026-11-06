package dev.thiagogomes.ticketsystem.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SeatStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Test
    void activeHoldStaysHeld() {
        assertThat(SeatStatus.effective(SeatStatus.HELD, NOW.plusSeconds(1), NOW)).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void holdReachingDeadlineIsAvailable() {
        assertThat(SeatStatus.effective(SeatStatus.HELD, NOW, NOW)).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(SeatStatus.effective(SeatStatus.HELD, NOW.minusSeconds(1), NOW)).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void soldAndAvailableIgnoreDeadline() {
        assertThat(SeatStatus.effective(SeatStatus.SOLD, NOW.minusSeconds(60), NOW)).isEqualTo(SeatStatus.SOLD);
        assertThat(SeatStatus.effective(SeatStatus.AVAILABLE, null, NOW)).isEqualTo(SeatStatus.AVAILABLE);
    }
}
