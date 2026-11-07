package dev.thiagogomes.ticketsystem.reservation.strategy;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ReservationStrategyConfigTest {

    private final ReservationStrategyConfig config = new ReservationStrategyConfig();

    @Test
    void picksStrategyIgnoringCaseAndSpaces() {
        assertThat(config.seatReservationStrategy(" Redis ", null, null, null)).isInstanceOf(RedisSeatReservation.class);
        assertThat(config.seatReservationStrategy("conditional", null, null, null)).isInstanceOf(ConditionalUpdateSeatReservation.class);
    }

    @Test
    void parsingDoesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); // "redis".toUpperCase() would be "REDİS" here
            assertThat(config.seatReservationStrategy("redis", null, null, null)).isInstanceOf(RedisSeatReservation.class);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void unknownStrategyFailsWithTheValidOptions() {
        assertThatIllegalStateException()
                .isThrownBy(() -> config.seatReservationStrategy("warmup-pessimistic", null, null, null))
                .withMessageContaining("warmup-pessimistic")
                .withMessageContaining("pessimistic, optimistic, conditional, redis");
    }
}
