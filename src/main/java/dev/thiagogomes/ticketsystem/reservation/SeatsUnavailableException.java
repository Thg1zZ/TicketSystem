package dev.thiagogomes.ticketsystem.reservation;

import dev.thiagogomes.ticketsystem.shared.error.ConflictException;

import java.util.List;
import java.util.Map;

public class SeatsUnavailableException extends ConflictException {

    public SeatsUnavailableException(List<Long> seatIds) {
        super("Seats are no longer available", Map.of("unavailableSeatIds", List.copyOf(seatIds)));
    }
}
