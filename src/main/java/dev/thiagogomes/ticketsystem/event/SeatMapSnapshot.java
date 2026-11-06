package dev.thiagogomes.ticketsystem.event;

/**
 * A seat map as of one database read. The ETag is derived from seat ids and statuses only, so every replica
 * computes the same value for the same state.
 */
public record SeatMapSnapshot(SeatMapResponse map, String etag) {
}
