package dev.thiagogomes.ticketsystem.ticketing;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.checkout.CheckoutService;
import dev.thiagogomes.ticketsystem.checkout.OrderConfirmedEvent;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.messaging.Messaging;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout -> outbox -> RabbitMQ -> ticket issuance -> outbox -> RabbitMQ -> e-mail, on real containers.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PostSaleFlowTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EventService eventService;
    @Autowired
    HoldService holdService;
    @Autowired
    CheckoutService checkoutService;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketIssuanceService issuanceService;
    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    AmqpAdmin amqpAdmin;

    @Test
    void confirmedOrderGetsTicketsWithQrCodeAndAnEmail(CapturedOutput output) throws Exception {
        UUID orderId = buyTwoSeats("fan");

        List<Ticket> tickets = awaitTickets(orderId, 2);
        assertThat(tickets).extracting(Ticket::getCode).doesNotHaveDuplicates().allSatisfy(code -> assertThat(code).hasSize(24));

        mockMvc.perform(get("/orders/{id}/tickets", orderId).header("X-User-Id", "fan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("VALID"));
        byte[] png = mockMvc.perform(get("/tickets/{id}/qr", tickets.getFirst().getId()).header("X-User-Id", "fan"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(png).startsWith(0x89, 'P', 'N', 'G');

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                assertThat(output).contains("[simulated e-mail] to=fan subject=\"Your 2 ticket(s) for order " + orderId));
    }

    @Test
    void ticketsAndQrCodesAreOnlyVisibleToTheOwner() throws Exception {
        UUID orderId = buyTwoSeats("owner");
        Ticket ticket = awaitTickets(orderId, 2).getFirst();

        mockMvc.perform(get("/orders/{id}/tickets", orderId).header("X-User-Id", "intruder"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/tickets/{id}/qr", ticket.getId()).header("X-User-Id", "intruder"))
                .andExpect(status().isNotFound());
    }

    @Test
    void redeliveredConfirmationDoesNotIssueTwice() {
        UUID orderId = buyTwoSeats("redelivery");
        awaitTickets(orderId, 2);

        assertThat(issuanceService.issueFor(orderId)).isZero();
        assertThat(ticketRepository.findByOrderIdOrderBySeatId(orderId)).hasSize(2);
    }

    @Test
    void messageThatKeepsFailingEndsInTheDeadLetterQueue() {
        String dlq = Messaging.deadLetterQueue(Messaging.TICKET_ISSUANCE_QUEUE);
        int before = messageCount(dlq);

        rabbitTemplate.convertAndSend(Messaging.EXCHANGE, Messaging.ORDER_CONFIRMED, new OrderConfirmedEvent(UUID.randomUUID(), "ghost"));

        await().atMost(ASYNC_TIMEOUT).until(() -> messageCount(dlq) == before + 1);
    }

    private UUID buyTwoSeats(String userId) {
        CreatedEvent event = EventFixtures.createEvent(eventService, 2);
        HoldResponse hold = holdService.create(event.id(), userId, event.seatIds());
        return checkoutService.checkout(hold.id(), userId, "postsale-" + UUID.randomUUID()).order().id();
    }

    private List<Ticket> awaitTickets(UUID orderId, int expected) {
        return await().atMost(ASYNC_TIMEOUT).until(() -> ticketRepository.findByOrderIdOrderBySeatId(orderId),
                tickets -> tickets.size() == expected);
    }

    private int messageCount(String queue) {
        Properties properties = amqpAdmin.getQueueProperties(queue);
        return properties == null ? 0 : ((Number) properties.get("QUEUE_MESSAGE_COUNT")).intValue();
    }
}
