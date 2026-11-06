package dev.thiagogomes.ticketsystem.checkout;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.MutableClock;
import dev.thiagogomes.ticketsystem.event.EventFixtures;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.event.EventService;
import dev.thiagogomes.ticketsystem.event.SeatRepository;
import dev.thiagogomes.ticketsystem.event.SeatStatus;
import dev.thiagogomes.ticketsystem.payment.FakePaymentGateway;
import dev.thiagogomes.ticketsystem.payment.PaymentResult;
import dev.thiagogomes.ticketsystem.reservation.HoldResponse;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import dev.thiagogomes.ticketsystem.reservation.HoldStatus;
import dev.thiagogomes.ticketsystem.reservation.SeatHoldRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paths where the money and the seats disagree. The gateway is a spy so each test scripts its answer.
 */
@IntegrationTest
class CheckoutPaymentFailureTest {

    @MockitoSpyBean
    FakePaymentGateway gateway;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    CheckoutService checkoutService;
    @Autowired
    HoldService holdService;
    @Autowired
    EventService eventService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    SeatHoldRepository holdRepository;
    @Autowired
    SeatRepository seatRepository;
    @Autowired
    MutableClock clock;

    CreatedEvent event;
    HoldResponse hold;

    @BeforeEach
    void holdOneSeat() {
        event = EventFixtures.createEvent(eventService, 1);
        hold = holdService.create(event.id(), "alice", event.seatIds());
    }

    @AfterEach
    void resetState() {
        clock.reset();
        reset(gateway);
    }

    @Test
    void declinedPaymentReturns402AndFreesTheSeats() throws Exception {
        doReturn(PaymentResult.declined("Insufficient funds")).when(gateway).charge(anyString(), any());

        checkout("key-declined-01")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value("Insufficient funds"))
                .andExpect(jsonPath("$.orderId").exists());

        assertThat(order("key-declined-01").getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(holdRepository.findById(hold.id()).orElseThrow().getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(seatRepository.findById(event.seatIds().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void replayOfDeclinedOrderDoesNotChargeAgain() throws Exception {
        doReturn(PaymentResult.declined("Insufficient funds")).when(gateway).charge(anyString(), any());
        checkout("key-declined-02").andExpect(status().isPaymentRequired());

        checkout("key-declined-02").andExpect(status().isPaymentRequired());

        verify(gateway, times(1)).charge(anyString(), any());
    }

    @Test
    void seatsLostDuringSlowPaymentAreRefunded() throws Exception {
        doAnswer(invocation -> {
            clock.advance(Duration.ofMinutes(11));
            holdService.create(event.id(), "bob", event.seatIds());
            return invocation.callRealMethod();
        }).when(gateway).charge(anyString(), any());

        checkout("key-lost-seat-1")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value(containsString("refunded")));

        Order order = order("key-lost-seat-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(gateway).refund(order.getId().toString());
        assertThat(seatRepository.findById(event.seatIds().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void gatewayFailureAnswers503AndClientRetryWithSameKeyCompletesTheOrder() throws Exception {
        doThrow(new IllegalStateException("gateway timeout")).doCallRealMethod().when(gateway).charge(anyString(), any());

        checkout("key-timeout-002")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
        assertThat(order("key-timeout-002").getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        checkout("key-timeout-002")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void orderStuckInPendingIsResumedByRecovery() throws Exception {
        doThrow(new IllegalStateException("gateway timeout")).doCallRealMethod().when(gateway).charge(anyString(), any());
        checkout("key-timeout-001").andExpect(status().isServiceUnavailable());
        assertThat(order("key-timeout-001").getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        clearInvocations(gateway);

        clock.advance(Duration.ofMinutes(3));
        checkoutService.resumePendingOrdersOlderThan(Duration.ofMinutes(2));

        assertThat(order("key-timeout-001").getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(seatRepository.findById(event.seatIds().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    private ResultActions checkout(String key) throws Exception {
        return mockMvc.perform(post("/holds/{id}/checkout", hold.id())
                .header("X-User-Id", "alice")
                .header("Idempotency-Key", key));
    }

    private Order order(String key) {
        return orderRepository.findByUserIdAndIdempotencyKey("alice", key).orElseThrow();
    }
}
