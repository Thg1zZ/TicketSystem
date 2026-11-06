package dev.thiagogomes.ticketsystem.reservation;

import dev.thiagogomes.ticketsystem.event.Seat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(UUID id, Long eventId, String userId, HoldStatus status, Instant expiresAt,
                           BigDecimal total, List<HeldSeat> seats) {

    public record HeldSeat(Long id, String sector, String row, int number, BigDecimal price) {
    }

    static HoldResponse of(SeatHold hold, List<Seat> seats) {
        List<HeldSeat> heldSeats = seats.stream()
                .map(seat -> new HeldSeat(seat.getId(), seat.getSector().getName(), seat.getRowLabel(),
                        seat.getSeatNumber(), seat.getSector().getPrice()))
                .toList();
        BigDecimal total = heldSeats.stream().map(HeldSeat::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new HoldResponse(hold.getId(), hold.getEventId(), hold.getUserId(), hold.getStatus(),
                hold.getExpiresAt(), total, heldSeats);
    }
}
