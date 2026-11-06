package dev.thiagogomes.ticketsystem.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record EventResponse(Long id, String name, String venue, Instant startsAt, List<SectorResponse> sectors) {

    public record SectorResponse(Long id, String name, BigDecimal price) {
    }

    static EventResponse from(Event event) {
        List<SectorResponse> sectors = event.getSectors().stream()
                .map(sector -> new SectorResponse(sector.getId(), sector.getName(), sector.getPrice()))
                .toList();
        return new EventResponse(event.getId(), event.getName(), event.getVenue(), event.getStartsAt(), sectors);
    }
}
