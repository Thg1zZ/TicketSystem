package dev.thiagogomes.ticketsystem.shared.error;

/**
 * A dependency failed and the outcome is unknown; the client may retry. Maps to 503.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
