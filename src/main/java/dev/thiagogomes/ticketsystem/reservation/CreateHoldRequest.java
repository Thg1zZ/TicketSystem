package dev.thiagogomes.ticketsystem.reservation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateHoldRequest(@NotNull @Size(min = 1, max = MAX_SEATS_PER_HOLD) List<@NotNull Long> seatIds) {

    public static final int MAX_SEATS_PER_HOLD = 4;
}
