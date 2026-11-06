package dev.thiagogomes.ticketsystem.event;

import dev.thiagogomes.ticketsystem.shared.error.BusinessRuleException;
import dev.thiagogomes.ticketsystem.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class EventService {

    static final int MAX_SEATS_PER_EVENT = 10_000;

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    public EventService(EventRepository eventRepository, SeatRepository seatRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.clock = clock;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        ensureUniqueSectorNames(request.sectors());
        ensureCapacityWithinLimit(request.sectors());

        Event event = new Event(request.name(), request.venue(), request.startsAt());
        request.sectors().forEach(sector ->
                event.addSector(sector.name(), sector.price()).addSeatRows(sector.rows(), sector.seatsPerRow()));

        return EventResponse.from(eventRepository.save(event));
    }

    @Transactional(readOnly = true)
    public EventResponse get(Long eventId) {
        return EventResponse.from(findEvent(eventId));
    }

    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(Long eventId) {
        Event event = findEvent(eventId);
        return SeatMapResponse.of(event, seatRepository.findSeatMap(eventId), clock.instant());
    }

    private Event findEvent(Long eventId) {
        return eventRepository.findWithSectorsById(eventId)
                .orElseThrow(() -> new NotFoundException("Event %d not found".formatted(eventId)));
    }

    private static void ensureUniqueSectorNames(List<CreateEventRequest.SectorRequest> sectors) {
        Set<String> names = new HashSet<>();
        sectors.stream()
                .map(sector -> sector.name().trim().toLowerCase(Locale.ROOT))
                .filter(name -> !names.add(name))
                .findFirst()
                .ifPresent(duplicated -> {
                    throw new BusinessRuleException("Sector names must be unique within an event: '%s'".formatted(duplicated));
                });
    }

    /**
     * Bounds the work of a single request (inserts now, seat map size later).
     */
    private static void ensureCapacityWithinLimit(List<CreateEventRequest.SectorRequest> sectors) {
        int seats = sectors.stream().mapToInt(sector -> sector.rows() * sector.seatsPerRow()).sum();
        if (seats > MAX_SEATS_PER_EVENT) {
            throw new BusinessRuleException("An event can have at most %d seats, got %d".formatted(MAX_SEATS_PER_EVENT, seats));
        }
    }
}
