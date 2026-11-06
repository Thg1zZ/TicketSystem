package dev.thiagogomes.ticketsystem.ticketing;

import dev.thiagogomes.ticketsystem.shared.ValidUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.USER_ID;

@Tag(name = "Tickets")
@RestController
class TicketController {

    private final TicketService ticketService;

    TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Operation(summary = "Tickets of an order (issued asynchronously, a few moments after confirmation)")
    @GetMapping("/orders/{orderId}/tickets")
    List<TicketResponse> list(@PathVariable UUID orderId, @RequestHeader(USER_ID) @ValidUserId String userId) {
        return ticketService.listForOrder(orderId, userId);
    }

    @Operation(summary = "QR code (PNG) to show at the gate")
    @GetMapping(value = "/tickets/{ticketId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    byte[] qrCode(@PathVariable UUID ticketId, @RequestHeader(USER_ID) @ValidUserId String userId) {
        return ticketService.qrCode(ticketId, userId);
    }
}
