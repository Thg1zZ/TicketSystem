package dev.thiagogomes.ticketsystem.reservation.strategy;

import dev.thiagogomes.ticketsystem.event.SeatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Locale;

/**
 * One place decides the active strategy. An unknown value fails at startup with the list of valid ones
 * instead of a "no qualifying bean" error.
 */
@Configuration(proxyBeanMethods = false)
class ReservationStrategyConfig {

    enum Strategy { PESSIMISTIC, OPTIMISTIC, CONDITIONAL, REDIS }

    @Bean
    SeatReservationStrategy seatReservationStrategy(@Value("${" + SeatReservationStrategy.PROPERTY + ":pessimistic}") String configured,
                                                    SeatRepository seatRepository,
                                                    StringRedisTemplate redis) {
        return switch (parse(configured)) {
            case PESSIMISTIC -> new PessimisticSeatReservation(seatRepository);
            case OPTIMISTIC -> new OptimisticSeatReservation(seatRepository);
            case CONDITIONAL -> new ConditionalUpdateSeatReservation(seatRepository);
            case REDIS -> new RedisSeatReservation(seatRepository, redis);
        };
    }

    private static Strategy parse(String configured) {
        try {
            return Strategy.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("Unknown %s '%s'; use one of: pessimistic, optimistic, conditional, redis"
                    .formatted(SeatReservationStrategy.PROPERTY, configured));
        }
    }
}
