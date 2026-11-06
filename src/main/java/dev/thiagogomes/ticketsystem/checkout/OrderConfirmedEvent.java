package dev.thiagogomes.ticketsystem.checkout;

import java.util.UUID;

public record OrderConfirmedEvent(UUID orderId, String userId) {
}
