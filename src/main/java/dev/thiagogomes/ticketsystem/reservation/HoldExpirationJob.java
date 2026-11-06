package dev.thiagogomes.ticketsystem.reservation;

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

    // ponytail: runs on every instance; harmless because the UPDATEs are conditional. Add ShedLock if it gets heavy.
    @Scheduled(fixedDelayString = "${ticket.hold.cleanup-interval}")
    void releaseExpiredHolds() {
        int released = holdService.releaseExpired();
        if (released > 0) {
            log.info("Released {} seats from expired holds", released);
        }
    }
}
