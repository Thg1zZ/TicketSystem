package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "ticket.reservation.strategy=conditional")
class ConditionalUpdateReservationTest extends ReservationStrategyContract {

    @Override
    Class<? extends SeatReservationStrategy> expectedStrategy() {
        return ConditionalUpdateSeatReservation.class;
    }
}
