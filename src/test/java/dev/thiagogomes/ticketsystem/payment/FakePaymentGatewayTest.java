package dev.thiagogomes.ticketsystem.payment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentGatewayTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void sameKeyReturnsTheFirstResultAndChargesOnce() {
        FakePaymentGateway gateway = gateway(0);

        PaymentResult first = gateway.charge("order-1", BigDecimal.TEN);
        PaymentResult again = gateway.charge("order-1", BigDecimal.TEN);

        assertThat(again).isEqualTo(first);
        assertThat(count("approved")).isEqualTo(1);
    }

    @Test
    void timedOutChargeKeepsGoingAndARetryFindsItInsteadOfChargingAgain() throws Exception {
        FakePaymentGateway slowGateway = new FakePaymentGateway(Duration.ofMillis(400), Duration.ofMillis(400), 0,
                Duration.ofMillis(100), meters);

        assertThatThrownBy(() -> slowGateway.charge("order-slow", BigDecimal.TEN)).isInstanceOf(PaymentTimeoutException.class);
        Thread.sleep(500); // the gateway finished on its side meanwhile

        assertThat(slowGateway.charge("order-slow", BigDecimal.TEN).approved()).isTrue();
        assertThat(meters.counter("payments", "outcome", "approved").count()).isEqualTo(1);
    }

    @Test
    void lookupNeverCreatesACharge() {
        FakePaymentGateway gateway = gateway(0);

        assertThat(gateway.findCharge("unknown")).isEmpty();
        assertThat(count("approved")).isZero();
    }

    @Test
    void onlyApprovedChargesAreRefundedAndOnlyOnce() {
        FakePaymentGateway declining = gateway(1);
        declining.charge("declined-order", BigDecimal.TEN);
        declining.refund("declined-order");
        declining.refund("never-charged");
        assertThat(count("refunded")).isZero();

        FakePaymentGateway approving = gateway(0);
        approving.charge("paid-order", BigDecimal.TEN);
        approving.refund("paid-order");
        approving.refund("paid-order");
        assertThat(count("refunded")).isEqualTo(1);
    }

    private FakePaymentGateway gateway(double failureRate) {
        return new FakePaymentGateway(Duration.ZERO, Duration.ZERO, failureRate, Duration.ofSeconds(5), meters);
    }

    private double count(String outcome) {
        var counter = meters.find("payments").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
