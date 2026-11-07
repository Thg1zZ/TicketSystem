package dev.thiagogomes.ticketsystem.payment;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Simulated card processor with configurable latency and decline rate. Like real gateways (Stripe, Adyen),
 * it is idempotent: the same key always returns the first result and never charges twice.
 */
// ponytail: in-memory and per instance, so charges are forgotten on restart and a retry routed to another replica
// can't see them; a real gateway deduplicates the key on its side
@Component
public class FakePaymentGateway {

    private final ConcurrentMap<String, FutureTask<PaymentResult>> charges = new ConcurrentHashMap<>();
    private final Set<String> refunds = ConcurrentHashMap.newKeySet();
    // The "remote side": a charge keeps going after the caller stops waiting, like a request already sent
    private final ExecutorService processor = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration minLatency;
    private final Duration maxLatency;
    private final double failureRate;
    private final Duration timeout;
    private final MeterRegistry meters;

    public FakePaymentGateway(@Value("${ticket.payment.min-latency}") Duration minLatency,
                              @Value("${ticket.payment.max-latency}") Duration maxLatency,
                              @Value("${ticket.payment.failure-rate}") double failureRate,
                              @Value("${ticket.payment.timeout}") Duration timeout,
                              MeterRegistry meters) {
        if (minLatency.compareTo(maxLatency) > 0 || failureRate < 0 || failureRate > 1 || !timeout.isPositive()) {
            throw new IllegalArgumentException("Invalid fake payment configuration");
        }
        this.minLatency = minLatency;
        this.maxLatency = maxLatency;
        this.failureRate = failureRate;
        this.timeout = timeout;
        this.meters = meters;
    }

    /**
     * Concurrent calls with the same key share one charge (memoizer). Waits at most the timeout; after that the
     * outcome is unknown to the caller, but a retry with the same key finds the same charge.
     *
     * @throws PaymentTimeoutException when the charge did not answer in time
     */
    public PaymentResult charge(String idempotencyKey, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        FutureTask<PaymentResult> charge = charges.computeIfAbsent(idempotencyKey, key -> {
            FutureTask<PaymentResult> started = new FutureTask<>(this::process);
            processor.execute(started);
            return started;
        });
        return await(idempotencyKey, charge);
    }

    /**
     * Looks a charge up without ever creating one (what a reconciliation job must use).
     * Waits if the charge is still in flight.
     */
    public Optional<PaymentResult> findCharge(String idempotencyKey) {
        FutureTask<PaymentResult> charge = charges.get(idempotencyKey);
        return charge == null ? Optional.empty() : Optional.of(await(idempotencyKey, charge));
    }

    /**
     * Idempotent, and only an approved charge has money to give back.
     */
    public void refund(String idempotencyKey) {
        boolean refundable = findCharge(idempotencyKey).filter(PaymentResult::approved).isPresent();
        if (refundable && refunds.add(idempotencyKey)) {
            meters.counter("payments", "outcome", "refunded").increment();
        }
    }

    private PaymentResult await(String idempotencyKey, FutureTask<PaymentResult> charge) {
        try {
            return charge.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillRunning) {
            throw new PaymentTimeoutException(idempotencyKey, timeout); // keep it: the charge may still go through
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for payment", e);
        } catch (ExecutionException e) {
            charges.remove(idempotencyKey, charge); // a crashed attempt must not poison later retries
            throw new IllegalStateException("Payment processing failed", e.getCause());
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
