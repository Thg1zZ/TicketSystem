package dev.thiagogomes.ticketsystem.event;

import java.time.Instant;

/**
 * Read-only projection for the seat map: avoids loading full entities for hundreds of seats.
 */
public record SeatView(Long id, Long sectorId, String rowLabel, int seatNumber, SeatStatus status, Instant heldUntil) {

    public SeatStatus effectiveStatus(Instant now) {
        return SeatStatus.effective(status, heldUntil, now);
    }
}
