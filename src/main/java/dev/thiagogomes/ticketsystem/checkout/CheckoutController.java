package dev.thiagogomes.ticketsystem.checkout;

import dev.thiagogomes.ticketsystem.shared.ValidUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.IDEMPOTENCY_KEY;
import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.IDEMPOTENT_REPLAYED;
import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.USER_ID;

@Tag(name = "Checkout")
@RestController
class CheckoutController {

    private final CheckoutService checkoutService;

    CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Operation(summary = "Pay for a hold. Repeating the call with the same Idempotency-Key never charges twice",
            description = "201 confirmed (Idempotent-Replayed tells whether it is a stored result); 402 payment declined; "
                    + "503 + Retry-After when the payment outcome is unknown: retry with the same Idempotency-Key")
    @PostMapping("/holds/{holdId}/checkout")
    ResponseEntity<OrderResponse> checkout(
            @PathVariable UUID holdId,
            @RequestHeader(USER_ID) @ValidUserId String userId,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            UriComponentsBuilder uriBuilder) {
        CheckoutResult result = checkoutService.checkout(holdId, userId, idempotencyKey);
        var location = uriBuilder.path("/orders/{id}").buildAndExpand(result.order().id()).toUri();
        return ResponseEntity.created(location)
                .header(IDEMPOTENT_REPLAYED, String.valueOf(result.replayed()))
                .body(result.order());
    }
}
