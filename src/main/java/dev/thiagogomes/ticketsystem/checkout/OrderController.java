package dev.thiagogomes.ticketsystem.checkout;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.USER_ID;

@Tag(name = "Orders")
@RestController
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "Get one of your orders")
    @GetMapping("/orders/{orderId}")
    OrderResponse get(@PathVariable UUID orderId, @RequestHeader(USER_ID) @NotBlank @Size(max = 64) String userId) {
        return orderService.find(orderId, userId);
    }

    @Operation(summary = "Cancel a confirmed order before the event: seats return to sale, tickets are voided, payment refunded")
    @PostMapping("/orders/{orderId}/cancel")
    OrderResponse cancel(@PathVariable UUID orderId, @RequestHeader(USER_ID) @NotBlank @Size(max = 64) String userId) {
        return orderService.cancel(orderId, userId);
    }
}
