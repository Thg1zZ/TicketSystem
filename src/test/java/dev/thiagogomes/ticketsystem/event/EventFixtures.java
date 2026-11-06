package dev.thiagogomes.ticketsystem.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class EventFixtures {

    public record CreatedEvent(long id, List<Long> seatIds) {
    }

    private EventFixtures() {
    }

    /**
     * One sector, one row, {@code seats} seats priced at 100.00.
     */
    public static CreatedEvent createEvent(EventService eventService, int seats) {
        var request = new CreateEventRequest("Test show", "Test arena", Instant.now().plus(30, ChronoUnit.DAYS),
                List.of(new CreateEventRequest.SectorRequest("Pista", new BigDecimal("100.00"), 1, seats)));
        long eventId = eventService.create(request).id();
        List<Long> seatIds = eventService.seatMap(eventId).sectors().getFirst().seats().stream()
                .map(SeatMapResponse.SeatResponse::id)
                .toList();
        return new CreatedEvent(eventId, seatIds);
    }
}
