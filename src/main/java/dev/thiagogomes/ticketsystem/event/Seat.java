package dev.thiagogomes.ticketsystem.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seat")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seat_seq")
    @SequenceGenerator(name = "seat_seq", sequenceName = "seat_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sector_id")
    private Sector sector;

    @Column(name = "row_label", nullable = false, length = 5)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "held_until")
    private Instant heldUntil;

    @Version
    private Long version;

    protected Seat() {
    }

    Seat(Sector sector, String rowLabel, int seatNumber) {
        this.sector = sector;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
    }

    public boolean isAvailableAt(Instant now) {
        return SeatStatus.effective(status, heldUntil, now) == SeatStatus.AVAILABLE;
    }

    public void hold(UUID holdId, Instant until) {
        this.status = SeatStatus.HELD;
        this.holdId = holdId;
        this.heldUntil = until;
    }

    public Long getId() {
        return id;
    }

    public Sector getSector() {
        return sector;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public UUID getHoldId() {
        return holdId;
    }
}
