package dev.thiagogomes.ticketsystem.event;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@Tag(name = "Events")
@RestController
@RequestMapping("/events")
class EventController {

    private final EventService eventService;

    EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @Operation(summary = "Create an event with sectors and a numbered seat layout")
    @PostMapping
    ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request, UriComponentsBuilder uriBuilder) {
        EventResponse created = eventService.create(request);
        var location = uriBuilder.path("/events/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get an event and its sectors")
    @GetMapping("/{eventId}")
    EventResponse get(@PathVariable Long eventId) {
        return eventService.get(eventId);
    }

    @Operation(summary = "Seat map with current availability (expired holds count as available)")
    @GetMapping("/{eventId}/seats")
    SeatMapResponse seatMap(@PathVariable Long eventId) {
        return eventService.seatMap(eventId);
    }
}
