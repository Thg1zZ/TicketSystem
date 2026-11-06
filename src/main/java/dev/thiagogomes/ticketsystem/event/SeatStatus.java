package dev.thiagogomes.ticketsystem.event;

import java.time.Instant;

public enum SeatStatus {
    AVAILABLE, HELD, SOLD;

    /**
     * A hold past its deadline is already free, even before the cleanup job releases it.
     */
    public static SeatStatus effective(SeatStatus stored, Instant heldUntil, Instant now) {
        boolean holdExpired = stored == HELD && heldUntil != null && !heldUntil.isAfter(now);
        return holdExpired ? AVAILABLE : stored;
    }
}
