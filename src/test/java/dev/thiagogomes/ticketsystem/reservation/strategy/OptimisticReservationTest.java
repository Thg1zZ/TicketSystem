package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "ticket.reservation.strategy=optimistic")
class OptimisticReservationTest extends ReservationStrategyContract {

    @Override
    Class<? extends SeatReservationStrategy> expectedStrategy() {
        return OptimisticSeatReservation.class;
    }
}
