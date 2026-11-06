package dev.thiagogomes.ticketsystem.shared.error;

/**
 * Missing or invalid credentials. Maps to 401.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
