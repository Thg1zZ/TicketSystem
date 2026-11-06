package dev.thiagogomes.ticketsystem.ticketing;

import dev.thiagogomes.ticketsystem.checkout.OrderService;
import dev.thiagogomes.ticketsystem.shared.error.ConflictException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.USER_ID;

@Tag(name = "Tickets")
@RestController
class TicketController {

    private final TicketRepository ticketRepository;
    private final OrderService orderService;

    TicketController(TicketRepository ticketRepository, OrderService orderService) {
        this.ticketRepository = ticketRepository;
        this.orderService = orderService;
    }

    @Operation(summary = "Tickets of an order (issued asynchronously, a few moments after confirmation)")
    @GetMapping("/orders/{orderId}/tickets")
    List<TicketResponse> list(@PathVariable UUID orderId, @RequestHeader(USER_ID) @NotBlank @Size(max = 64) String userId) {
        orderService.find(orderId, userId); // 404 unless the caller owns the order
        return ticketRepository.findByOrderIdOrderBySeatId(orderId).stream().map(TicketResponse::from).toList();
    }

    @Operation(summary = "QR code (PNG) to show at the gate")
    @GetMapping(value = "/tickets/{ticketId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    byte[] qrCode(@PathVariable UUID ticketId, @RequestHeader(USER_ID) @NotBlank @Size(max = 64) String userId) {
        Ticket ticket = ticketRepository.findOwned(ticketId, userId)
                .orElseThrow(() -> new NotFoundException("Ticket %s not found".formatted(ticketId)));
        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new ConflictException("Ticket was cancelled");
        }
        return QrCodes.png(ticket.getCode());
    }

    record TicketResponse(UUID id, Long seatId, String code, TicketStatus status, Instant issuedAt) {

        static TicketResponse from(Ticket ticket) {
            return new TicketResponse(ticket.getId(), ticket.getSeatId(), ticket.getCode(), ticket.getStatus(), ticket.getIssuedAt());
        }
    }
}
