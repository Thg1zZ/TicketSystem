package dev.thiagogomes.ticketsystem.checkout;

import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Another user's order answers 404, never 403: existence is not revealed.
     */
    @Transactional(readOnly = true)
    public OrderResponse find(UUID orderId, String userId) {
        return orderRepository.findWithItemsByIdAndUserId(orderId, userId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new NotFoundException("Order %s not found".formatted(orderId)));
    }
}
