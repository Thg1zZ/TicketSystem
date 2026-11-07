package dev.thiagogomes.ticketsystem.shared.error;

/**
 * The caller is identified but not allowed to do this yet (e.g. holding seats without a queue pass). Maps to 403.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
