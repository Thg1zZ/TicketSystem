package dev.thiagogomes.ticketsystem.checkout;

/**
 * @param replayed true when the order was already settled by an earlier request with the same Idempotency-Key
 */
public record CheckoutResult(OrderResponse order, boolean replayed) {
}
