package dev.thiagogomes.ticketsystem.shared;

public final class RequestHeaders {

    /**
     * Simplified identity for this project scope. NOT authentication: any client can send any value.
     */
    public static final String USER_ID = "X-User-Id";

    /**
     * Shared secret required by admin operations (see {@link AdminTokenGuard}).
     */
    public static final String ADMIN_TOKEN = "X-Admin-Token";

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * Signed admission pass from the virtual queue, required to hold seats while the queue is on.
     */
    public static final String QUEUE_PASS = "X-Queue-Pass";

    /**
     * Response header telling the client this result was stored by an earlier request with the same key.
     */
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private RequestHeaders() {
    }
}
