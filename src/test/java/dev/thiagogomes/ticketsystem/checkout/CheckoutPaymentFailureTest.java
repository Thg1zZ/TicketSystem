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
import dev.thiagogomes.ticketsystem.shared.error.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paths where the money and the seats disagree. The gateway is a spy so each test scripts its answer.
 * Small circuit breaker and bulkhead limits so their behavior shows within a test.
 */
@IntegrationTest
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.payment.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.payment.minimum-number-of-calls=4",
        "ticket.payment.max-concurrent=2"
})
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
    @Autowired
    CircuitBreakerRegistry circuitBreakers;

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
        circuitBreakers.circuitBreaker("payment").reset();
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
    void declinedOrderCannotBeCancelled() throws Exception {
        doReturn(PaymentResult.declined("Insufficient funds")).when(gateway).charge(anyString(), any());
        checkout("key-declined-03").andExpect(status().isPaymentRequired());

        mockMvc.perform(post("/orders/{id}/cancel", order("key-declined-03").getId()).header("X-User-Id", "alice"))
                .andExpect(status().isConflict());
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
    void abandonedOrderThatWasNeverChargedIsClosedWithoutCharging() throws Exception {
        doThrow(new IllegalStateException("gateway timeout")).when(gateway).charge(anyString(), any());
        checkout("key-timeout-001").andExpect(status().isServiceUnavailable());
        clearInvocations(gateway);

        clock.advance(Duration.ofMinutes(3));
        checkoutService.resumePendingOrdersOlderThan(Duration.ofMinutes(2));

        assertThat(order("key-timeout-001").getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(gateway, never()).charge(anyString(), any());
        assertThat(seatRepository.findById(event.seatIds().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void orderChargedRightBeforeACrashIsConfirmedByRecovery() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("instance crashed after the charge");
        }).when(gateway).charge(anyString(), any());
        checkout("key-crashed-001").andExpect(status().isServiceUnavailable());
        assertThat(order("key-crashed-001").getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        clearInvocations(gateway);

        clock.advance(Duration.ofMinutes(3));
        checkoutService.resumePendingOrdersOlderThan(Duration.ofMinutes(2));

        assertThat(order("key-crashed-001").getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(gateway, never()).charge(anyString(), any());
        assertThat(seatRepository.findById(event.seatIds().getFirst()).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void repeatedGatewayErrorsOpenTheCircuitSoLaterCheckoutsFailFastWithoutCallingIt() throws Exception {
        CreatedEvent event = EventFixtures.createEvent(eventService, 5);
        List<HoldResponse> holds = IntStream.range(0, 5)
                .mapToObj(i -> holdService.create(event.id(), "circuit-" + i, List.of(event.seatIds().get(i))))
                .toList();
        doThrow(new IllegalStateException("gateway down")).when(gateway).charge(anyString(), any());

        for (int i = 0; i < 4; i++) {
            checkout(holds.get(i), "circuit-" + i, "key-circuit-0" + i).andExpect(status().isServiceUnavailable());
        }
        checkout(holds.get(4), "circuit-4", "key-circuit-04")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("nothing was charged")));

        verify(gateway, times(4)).charge(anyString(), any()); // the fifth never reached the gateway
    }

    @Test
    void paymentsAboveTheBulkheadLimitAreRejectedAtOnce() throws Exception {
        CreatedEvent event = EventFixtures.createEvent(eventService, 3);
        List<HoldResponse> holds = IntStream.range(0, 3)
                .mapToObj(i -> holdService.create(event.id(), "bulkhead-" + i, List.of(event.seatIds().get(i))))
                .toList();
        CountDownLatch slowGateway = new CountDownLatch(1);
        CountDownLatch twoInFlight = new CountDownLatch(2);
        doAnswer(invocation -> {
            twoInFlight.countDown();
            slowGateway.await();
            return invocation.callRealMethod();
        }).when(gateway).charge(anyString(), any());

        List<CompletableFuture<?>> inFlight = IntStream.range(0, 2).<CompletableFuture<?>>mapToObj(i -> CompletableFuture.runAsync(() ->
                checkoutService.checkout(holds.get(i).id(), "bulkhead-" + i, "key-bulkhead-0" + i))).toList();
        twoInFlight.await();

        assertThatThrownBy(() -> checkoutService.checkout(holds.get(2).id(), "bulkhead-2", "key-bulkhead-02"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("nothing was charged");
        slowGateway.countDown();
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
    }

    private ResultActions checkout(HoldResponse otherHold, String userId, String key) throws Exception {
        return mockMvc.perform(post("/holds/{id}/checkout", otherHold.id())
                .header("X-User-Id", userId)
                .header("Idempotency-Key", key));
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
