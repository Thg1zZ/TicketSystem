package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.IntegrationTest;

/**
 * Default strategy: no property override, so it shares the main test context.
 */
@IntegrationTest
class PessimisticReservationTest extends ReservationStrategyContract {

    @Override
    Class<? extends SeatReservationStrategy> expectedStrategy() {
        return PessimisticSeatReservation.class;
    }
}
