package dev.thiagogomes.ticketsystem.ticketing;

import java.util.UUID;

public record TicketsIssuedEvent(UUID orderId, String userId, int ticketCount) {
}
