package dev.thiagogomes.ticketsystem.reservation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.USER_ID;

@Tag(name = "Holds")
@RestController
class HoldController {

    private final HoldService holdService;

    HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @Operation(summary = "Temporarily hold up to 4 seats for 10 minutes")
    @PostMapping("/events/{eventId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    HoldResponse create(@PathVariable Long eventId,
                        @RequestHeader(USER_ID) @NotBlank @Size(max = 64) String userId,
                        @Valid @RequestBody CreateHoldRequest request) {
        return holdService.create(eventId, userId, request.seatIds());
    }

    @Operation(summary = "Release a hold, making its seats available again")
    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID holdId, @RequestHeader(USER_ID) @NotBlank @Size(max = 64) String userId) {
        holdService.release(holdId, userId);
    }
}
