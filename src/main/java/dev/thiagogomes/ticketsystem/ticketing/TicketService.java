package dev.thiagogomes.ticketsystem.ticketing;

import dev.thiagogomes.ticketsystem.checkout.OrderService;
import dev.thiagogomes.ticketsystem.shared.error.ConflictException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Ticket reads. Both operations answer 404 for tickets of other users.
 */
@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final OrderService orderService;

    public TicketService(TicketRepository ticketRepository, OrderService orderService) {
        this.ticketRepository = ticketRepository;
        this.orderService = orderService;
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> listForOrder(UUID orderId, String userId) {
        orderService.find(orderId, userId);
        return ticketRepository.findByOrderIdOrderBySeatId(orderId).stream().map(TicketResponse::from).toList();
    }

    /**
     * Only a valid ticket gets a QR code: a cancelled one must not be scannable.
     */
    @Transactional(readOnly = true)
    public byte[] qrCode(UUID ticketId, String userId) {
        Ticket ticket = ticketRepository.findOwned(ticketId, userId)
                .orElseThrow(() -> new NotFoundException("Ticket %s not found".formatted(ticketId)));
        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new ConflictException("Ticket was cancelled");
        }
        return QrCodes.png(ticket.getCode());
    }
}
