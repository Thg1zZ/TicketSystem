package dev.thiagogomes.ticketsystem.shared;

import dev.thiagogomes.ticketsystem.shared.error.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Redis is an accelerator here, never the source of truth, so every use of it has a plan B. Calls go through one
 * circuit breaker: once Redis is clearly down, callers skip straight to the plan B instead of each one waiting
 * for a timeout. Fallbacks are counted per operation ({@code redis.fallback}).
 */
@Component
public class RedisCircuit {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuit.class);

    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meters;

    public RedisCircuit(CircuitBreakerRegistry circuitBreakers, MeterRegistry meters) {
        this.circuitBreaker = circuitBreakers.circuitBreaker("redis");
        this.meters = meters;
    }

    /**
     * Degrades: when Redis is unavailable the result of {@code fallback} is used instead.
     */
    public <T> T callOrElse(String operation, Supplier<T> redisCall, Supplier<T> fallback) {
        try {
            return circuitBreaker.executeSupplier(redisCall);
        } catch (CallNotPermittedException | DataAccessException unavailable) {
            meters.counter("redis.fallback", "operation", operation).increment();
            log.debug("Redis unavailable for {}, using the fallback: {}", operation, unavailable.getMessage());
            return fallback.get();
        }
    }

    /**
     * Fails closed: for features that must not run without Redis (admitting buyers through the queue).
     */
    public <T> T callOrUnavailable(String operation, Supplier<T> redisCall) {
        return callOrElse(operation, redisCall, () -> {
            throw new ServiceUnavailableException("%s is temporarily unavailable; retry later".formatted(operation));
        });
    }
}
