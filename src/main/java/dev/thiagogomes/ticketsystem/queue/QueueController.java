package dev.thiagogomes.ticketsystem.queue;

import dev.thiagogomes.ticketsystem.shared.ValidUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.USER_ID;

@Tag(name = "Queue")
@RestController
class QueueController {

    private final VirtualQueue queue;

    QueueController(VirtualQueue queue) {
        this.queue = queue;
    }

    @Operation(summary = "Join the virtual queue of an event (idempotent: keeps your place)",
            description = "OPEN when the queue is off (no pass needed); otherwise WAITING with your position, or "
                    + "ADMITTED with the pass to send as X-Queue-Pass when holding seats")
    @PostMapping("/events/{eventId}/queue")
    QueueStatusResponse join(@PathVariable long eventId, @RequestHeader(USER_ID) @ValidUserId String userId) {
        return queue.join(eventId, userId);
    }

    @Operation(summary = "Your place in the queue, or your pass once admitted (poll every few seconds)",
            description = "404 when you are not in the queue (never joined, or your pass expired)")
    @GetMapping("/events/{eventId}/queue")
    QueueStatusResponse status(@PathVariable long eventId, @RequestHeader(USER_ID) @ValidUserId String userId) {
        return queue.status(eventId, userId);
    }
}
