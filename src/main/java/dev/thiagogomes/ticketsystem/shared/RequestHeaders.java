package dev.thiagogomes.ticketsystem.shared;

public final class RequestHeaders {

    /**
     * Simplified identity for this project scope. NOT authentication: any client can send any value.
     */
    public static final String USER_ID = "X-User-Id";

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * Response header telling the client this result was stored by an earlier request with the same key.
     */
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private RequestHeaders() {
    }
}
