package dev.thiagogomes.ticketsystem.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

class SectorTest {

    private final Sector sector = new Event("Show", "Arena", Instant.now()).addSector("Pista", new BigDecimal("100.00"));

    @Test
    void createsRowsLabeledByLetterAndSeatsNumberedFromOne() {
        sector.addSeatRows(2, 3);

        assertThat(sector.getSeats())
                .extracting(Seat::getRowLabel, Seat::getSeatNumber, Seat::getStatus)
                .containsExactly(
                        tuple("A", 1, SeatStatus.AVAILABLE), tuple("A", 2, SeatStatus.AVAILABLE), tuple("A", 3, SeatStatus.AVAILABLE),
                        tuple("B", 1, SeatStatus.AVAILABLE), tuple("B", 2, SeatStatus.AVAILABLE), tuple("B", 3, SeatStatus.AVAILABLE));
    }

    @Test
    void rejectsLayoutBeyondAlphabet() {
        assertThatIllegalArgumentException().isThrownBy(() -> sector.addSeatRows(Sector.MAX_ROWS + 1, 10));
    }
}
