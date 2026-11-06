package dev.thiagogomes.ticketsystem.checkout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID id, UUID holdId, String userId, OrderStatus status, BigDecimal total,
                            String paymentReference, Instant createdAt, List<Item> items) {

    public record Item(Long seatId, BigDecimal price) {
    }

    static OrderResponse from(Order order) {
        List<Item> items = order.getItems().stream().map(item -> new Item(item.getSeatId(), item.getPrice())).toList();
        return new OrderResponse(order.getId(), order.getHoldId(), order.getUserId(), order.getStatus(),
                order.getTotalAmount(), order.getPaymentReference(), order.getCreatedAt(), items);
    }
}
