package dev.thiagogomes.ticketsystem.reservation;

import dev.thiagogomes.ticketsystem.queue.VirtualQueue;
import dev.thiagogomes.ticketsystem.shared.ValidUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.QUEUE_PASS;
import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.USER_ID;

@Tag(name = "Holds")
@RestController
class HoldController {

    private final HoldService holdService;
    private final VirtualQueue queue;

    HoldController(HoldService holdService, VirtualQueue queue) {
        this.holdService = holdService;
        this.queue = queue;
    }

    @Operation(summary = "Temporarily hold up to 4 seats for 10 minutes",
            description = "With the virtual queue on, requires the X-Queue-Pass received when admitted (403 otherwise)")
    @PostMapping("/events/{eventId}/holds")
    ResponseEntity<HoldResponse> create(@PathVariable Long eventId,
                                        @RequestHeader(USER_ID) @ValidUserId String userId,
                                        @RequestHeader(value = QUEUE_PASS, required = false) String queuePass,
                                        @Valid @RequestBody CreateHoldRequest request,
                                        UriComponentsBuilder uriBuilder) {
        queue.requireAdmission(eventId, userId, queuePass);
        HoldResponse hold = holdService.create(eventId, userId, request.seatIds());
        var location = uriBuilder.path("/holds/{id}").buildAndExpand(hold.id()).toUri();
        return ResponseEntity.created(location).body(hold);
    }

    @Operation(summary = "Get one of your holds (status, deadline and the seats it still owns)")
    @GetMapping("/holds/{holdId}")
    HoldResponse get(@PathVariable UUID holdId, @RequestHeader(USER_ID) @ValidUserId String userId) {
        return holdService.find(holdId, userId);
    }

    @Operation(summary = "Release a hold, making its seats available again")
    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID holdId, @RequestHeader(USER_ID) @ValidUserId String userId) {
        holdService.release(holdId, userId);
    }
}
