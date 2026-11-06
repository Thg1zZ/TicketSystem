package dev.thiagogomes.ticketsystem.payment;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulated card processor with configurable latency and decline rate. Like real gateways (Stripe, Adyen),
 * it is idempotent: the same key always returns the first result and never charges twice.
 */
// ponytail: in-memory, so charges are forgotten on restart; a real gateway keeps them on its side
@Component
public class FakePaymentGateway {

    private final ConcurrentMap<String, FutureTask<PaymentResult>> charges = new ConcurrentHashMap<>();
    private final Set<String> refunds = ConcurrentHashMap.newKeySet();
    private final Duration minLatency;
    private final Duration maxLatency;
    private final double failureRate;
    private final MeterRegistry meters;

    public FakePaymentGateway(@Value("${ticket.payment.min-latency}") Duration minLatency,
                              @Value("${ticket.payment.max-latency}") Duration maxLatency,
                              @Value("${ticket.payment.failure-rate}") double failureRate,
                              MeterRegistry meters) {
        if (minLatency.compareTo(maxLatency) > 0 || failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("Invalid fake payment configuration");
        }
        this.minLatency = minLatency;
        this.maxLatency = maxLatency;
        this.failureRate = failureRate;
        this.meters = meters;
    }

    /**
     * Concurrent calls with the same key share one in-flight charge (memoizer): the first caller
     * runs it, the others wait for the same result.
     */
    public PaymentResult charge(String idempotencyKey, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        FutureTask<PaymentResult> newCharge = new FutureTask<>(this::process);
        FutureTask<PaymentResult> charge = charges.putIfAbsent(idempotencyKey, newCharge);
        if (charge == null) {
            charge = newCharge;
            charge.run();
        }
        try {
            return charge.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for payment", e);
        } catch (ExecutionException e) {
            charges.remove(idempotencyKey, charge); // a crashed attempt must not poison later retries
            throw new IllegalStateException("Payment processing failed", e.getCause());
        }
    }

    public void refund(String idempotencyKey) {
        if (charges.containsKey(idempotencyKey) && refunds.add(idempotencyKey)) {
            meters.counter("payments", "outcome", "refunded").increment();
        }
    }

    private PaymentResult process() throws InterruptedException {
        Thread.sleep(randomLatency());
        boolean declined = ThreadLocalRandom.current().nextDouble() < failureRate;
        meters.counter("payments", "outcome", declined ? "declined" : "approved").increment();
        return declined
                ? PaymentResult.declined("Card declined by issuer (simulated)")
                : PaymentResult.approved("pay_" + UUID.randomUUID());
    }

    private Duration randomLatency() {
        long spread = maxLatency.toMillis() - minLatency.toMillis();
        return minLatency.plusMillis(spread == 0 ? 0 : ThreadLocalRandom.current().nextLong(spread + 1));
    }
}
