package dev.thiagogomes.ticketsystem.event;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "sector")
public class Sector {

    static final int MAX_ROWS = 26;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sector_seq")
    @SequenceGenerator(name = "sector_seq", sequenceName = "sector_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @OneToMany(mappedBy = "sector", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Seat> seats = new ArrayList<>();

    protected Sector() {
    }

    Sector(Event event, String name, BigDecimal price) {
        this.event = event;
        this.name = name;
        this.price = price;
    }

    /**
     * Rows are labeled A, B, C...; seats in each row are numbered from 1.
     */
    public void addSeatRows(int rows, int seatsPerRow) {
        if (rows < 1 || rows > MAX_ROWS || seatsPerRow < 1) {
            throw new IllegalArgumentException("Invalid layout: %d rows x %d seats".formatted(rows, seatsPerRow));
        }
        for (int row = 0; row < rows; row++) {
            String rowLabel = String.valueOf((char) ('A' + row));
            for (int number = 1; number <= seatsPerRow; number++) {
                seats.add(new Seat(this, rowLabel, number));
            }
        }
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public List<Seat> getSeats() {
        return Collections.unmodifiableList(seats);
    }
}
