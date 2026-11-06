package dev.thiagogomes.ticketsystem.shared.error;

import java.util.Map;

/**
 * The request is valid but loses to the current state (seat taken, hold already active...). Maps to 409.
 */
public class ConflictException extends RuntimeException {

    private final Map<String, Object> details;

    public ConflictException(String message) {
        this(message, Map.of());
    }

    public ConflictException(String message, Map<String, Object> details) {
        super(message);
        this.details = Map.copyOf(details);
    }

    public Map<String, Object> details() {
        return details;
    }
}
