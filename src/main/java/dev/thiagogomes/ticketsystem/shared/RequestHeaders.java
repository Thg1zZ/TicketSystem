package dev.thiagogomes.ticketsystem.shared;

public final class RequestHeaders {

    /**
     * Simplified identity for this project scope. NOT authentication: any client can send any value.
     */
    public static final String USER_ID = "X-User-Id";

    private RequestHeaders() {
    }
}
