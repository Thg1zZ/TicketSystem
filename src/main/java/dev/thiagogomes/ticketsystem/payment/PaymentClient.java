package dev.thiagogomes.ticketsystem.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Our side of the gateway call, where the protections live (the gateway itself is "someone else's system"):
 * <ul>
 *   <li>bulkhead: at most {@code ticket.payment.max-concurrent} charges in flight per instance; more are rejected
 *   at once ({@code InvocationRejectedException}) instead of piling up behind a slow gateway;</li>
 *   <li>circuit breaker: after repeated errors or timeouts it stops calling for a while and fails fast
 *   ({@code CallNotPermittedException}), giving the gateway room to recover;</li>
 *   <li>timeout: enforced by the gateway call itself ({@link PaymentTimeoutException}).</li>
 * </ul>
 * A declined card is a normal answer, not a failure: it never opens the circuit.
 */
@Component
public class PaymentClient {

    private final FakePaymentGateway gateway;
    private final CircuitBreaker circuitBreaker;

    public PaymentClient(FakePaymentGateway gateway, CircuitBreakerRegistry circuitBreakers) {
        this.gateway = gateway;
        this.circuitBreaker = circuitBreakers.circuitBreaker("payment");
    }

    @ConcurrencyLimit(limitString = "${ticket.payment.max-concurrent}", policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
    public PaymentResult charge(String idempotencyKey, BigDecimal amount) {
        return circuitBreaker.executeSupplier(() -> gateway.charge(idempotencyKey, amount));
    }
}
