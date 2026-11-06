package dev.thiagogomes.ticketsystem.reservation.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ReservationStrategyConfigTest {

    private final ReservationStrategyConfig config = new ReservationStrategyConfig();

    @Test
    void picksStrategyIgnoringCaseAndSpaces() {
        assertThat(config.seatReservationStrategy(" Redis ", null, null)).isInstanceOf(RedisSeatReservation.class);
        assertThat(config.seatReservationStrategy("conditional", null, null)).isInstanceOf(ConditionalUpdateSeatReservation.class);
    }

    @Test
    void unknownStrategyFailsWithTheValidOptions() {
        assertThatIllegalStateException()
                .isThrownBy(() -> config.seatReservationStrategy("warmup-pessimistic", null, null))
                .withMessageContaining("warmup-pessimistic")
                .withMessageContaining("pessimistic, optimistic, conditional, redis");
    }
}
