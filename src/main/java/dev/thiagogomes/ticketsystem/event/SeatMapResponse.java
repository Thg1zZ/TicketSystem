package dev.thiagogomes.ticketsystem.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

public record SeatMapResponse(Long eventId, List<SectorSeats> sectors) {

    public record SectorSeats(Long sectorId, String name, BigDecimal price, long available, List<SeatResponse> seats) {
    }

    public record SeatResponse(Long id, String row, int number, SeatStatus status) {
    }

    static SeatMapResponse of(Event event, List<SeatView> seatViews, Instant now) {
        Map<Long, List<SeatResponse>> seatsBySector = seatViews.stream()
                .collect(groupingBy(SeatView::sectorId, mapping(view -> toResponse(view, now), toList())));

        List<SectorSeats> sectors = event.getSectors().stream()
                .map(sector -> {
                    List<SeatResponse> seats = seatsBySector.getOrDefault(sector.getId(), List.of());
                    long available = seats.stream().filter(seat -> seat.status() == SeatStatus.AVAILABLE).count();
                    return new SectorSeats(sector.getId(), sector.getName(), sector.getPrice(), available, seats);
                })
                .toList();
        return new SeatMapResponse(event.getId(), sectors);
    }

    private static SeatResponse toResponse(SeatView view, Instant now) {
        return new SeatResponse(view.id(), view.rowLabel(), view.seatNumber(), view.effectiveStatus(now));
    }
}
