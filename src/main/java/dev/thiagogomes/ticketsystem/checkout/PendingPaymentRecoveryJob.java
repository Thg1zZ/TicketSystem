package dev.thiagogomes.ticketsystem.checkout;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Without this, a client that disappears mid-checkout could leave a charge without a ticket.
 * Safe to overlap with client retries: the gateway call and the confirmation are idempotent.
 */
@Component
class PendingPaymentRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingPaymentRecoveryJob.class);

    private final CheckoutService checkoutService;
    private final Duration staleAfter;

    PendingPaymentRecoveryJob(CheckoutService checkoutService, @Value("${ticket.checkout.pending-stale-after}") Duration staleAfter) {
        this.checkoutService = checkoutService;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${ticket.checkout.recovery-interval}")
    @SchedulerLock(name = "pending-payment-recovery", lockAtLeastFor = "${ticket.checkout.recovery-interval}")
    void resumeStalePayments() {
        int resumed = checkoutService.resumePendingOrdersOlderThan(staleAfter);
        if (resumed > 0) {
            log.info("Resumed {} stale pending orders", resumed);
        }
    }
}
