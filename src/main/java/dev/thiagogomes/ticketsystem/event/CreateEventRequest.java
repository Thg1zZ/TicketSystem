package dev.thiagogomes.ticketsystem.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CreateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String venue,
        @NotNull @Future Instant startsAt,
        @NotEmpty @Size(max = 20) List<@Valid @NotNull SectorRequest> sectors) {

    public record SectorRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
            @Min(1) @Max(Sector.MAX_ROWS) int rows,
            @Min(1) @Max(100) int seatsPerRow) {
    }
}
