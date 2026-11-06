package dev.thiagogomes.ticketsystem.shared.error;

import java.util.Map;

/**
 * The charge was not approved. Maps to 402.
 */
public class PaymentRequiredException extends RuntimeException {

    private final Map<String, Object> details;

    public PaymentRequiredException(String message, Map<String, Object> details) {
        super(message);
        this.details = Map.copyOf(details);
    }

    public Map<String, Object> details() {
        return details;
    }
}
