package dev.thiagogomes.ticketsystem.reservation;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class HoldExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirationJob.class);

    private final HoldService holdService;

    HoldExpirationJob(HoldService holdService) {
        this.holdService = holdService;
    }

    /**
     * Once per interval in the whole cluster: the lock is kept for a full interval, so the other instances skip.
     */
    @Scheduled(fixedDelayString = "${ticket.hold.cleanup-interval}")
    @SchedulerLock(name = "hold-expiration", lockAtLeastFor = "${ticket.hold.cleanup-interval}")
    void releaseExpiredHolds() {
        int released = holdService.releaseExpired();
        if (released > 0) {
            log.info("Released {} seats from expired holds", released);
        }
    }
}
