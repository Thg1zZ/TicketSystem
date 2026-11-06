package dev.thiagogomes.ticketsystem.event;

import dev.thiagogomes.ticketsystem.shared.AdminTokenGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

import static dev.thiagogomes.ticketsystem.shared.RequestHeaders.ADMIN_TOKEN;

@Tag(name = "Events")
@RestController
@RequestMapping("/events")
class EventController {

    private final EventService eventService;
    private final SeatMapCache seatMapCache;
    private final SeatMapStreams seatMapStreams;
    private final AdminTokenGuard adminTokenGuard;

    EventController(EventService eventService, SeatMapCache seatMapCache, SeatMapStreams seatMapStreams,
                    AdminTokenGuard adminTokenGuard) {
        this.eventService = eventService;
        this.seatMapCache = seatMapCache;
        this.seatMapStreams = seatMapStreams;
        this.adminTokenGuard = adminTokenGuard;
    }

    @Operation(summary = "Create an event with sectors and a numbered seat layout (admin only, up to 10,000 seats)",
            description = "Requires X-Admin-Token; 401 when it is missing or wrong")
    @PostMapping
    ResponseEntity<EventResponse> create(@RequestHeader(value = ADMIN_TOKEN, required = false) String adminToken,
                                         @Valid @RequestBody CreateEventRequest request,
                                         UriComponentsBuilder uriBuilder) {
        adminTokenGuard.check(adminToken);
        EventResponse created = eventService.create(request);
        var location = uriBuilder.path("/events/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get an event and its sectors")
    @GetMapping("/{eventId}")
    EventResponse get(@PathVariable Long eventId) {
        return eventService.get(eventId);
    }

    @Operation(summary = "Seat map with current availability (expired holds count as available)",
            description = "May be up to ~0.5 s old. Send If-None-Match with the last ETag to get 304 when nothing changed")
    @GetMapping("/{eventId}/seats")
    ResponseEntity<SeatMapResponse> seatMap(@PathVariable Long eventId, WebRequest request) {
        SeatMapSnapshot snapshot = seatMapCache.get(eventId);
        if (request.checkNotModified(snapshot.etag())) {
            return null; // 304 without a body
        }
        // no-cache, not no-store: nothing secret here, so browsers may keep it and revalidate with the ETag
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).eTag(snapshot.etag()).body(snapshot.map());
    }

    @Operation(summary = "Live seat map (Server-Sent Events)",
            description = "A 'seats' event with the full map on connect and whenever it changes, plus a heartbeat "
                    + "comment every 15 s. 503 when this instance has too many open streams (poll instead)")
    @GetMapping(value = "/{eventId}/seats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> seatMapStream(@PathVariable Long eventId) {
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no") // nginx passes events through instead of buffering them
                .body(seatMapStreams.subscribe(eventId));
    }
}
