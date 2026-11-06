package dev.thiagogomes.ticketsystem.checkout;

import java.util.UUID;

public record OrderCancelledEvent(UUID orderId, String userId) {
}
