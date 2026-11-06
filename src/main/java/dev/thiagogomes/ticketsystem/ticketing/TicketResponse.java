package dev.thiagogomes.ticketsystem.ticketing;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code code} is what the gate scanner trusts: only ever returned to the order owner.
 */
public record TicketResponse(UUID id, Long seatId, String code, TicketStatus status, Instant issuedAt) {

    static TicketResponse from(Ticket ticket) {
        return new TicketResponse(ticket.getId(), ticket.getSeatId(), ticket.getCode(), ticket.getStatus(), ticket.getIssuedAt());
    }
}
