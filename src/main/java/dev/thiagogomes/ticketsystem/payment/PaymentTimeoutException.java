package dev.thiagogomes.ticketsystem.payment;

import java.time.Duration;

/**
 * The gateway did not answer in time. The charge may still complete: retry with the same key to learn the outcome.
 */
public class PaymentTimeoutException extends RuntimeException {

    public PaymentTimeoutException(String idempotencyKey, Duration timeout) {
        super("Payment %s did not answer within %d ms".formatted(idempotencyKey, timeout.toMillis()));
    }
}
