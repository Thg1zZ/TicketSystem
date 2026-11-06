package dev.thiagogomes.ticketsystem.event;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "event")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "event_seq")
    @SequenceGenerator(name = "event_seq", sequenceName = "event_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Sector> sectors = new ArrayList<>();

    protected Event() {
    }

    public Event(String name, String venue, Instant startsAt) {
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
    }

    public Sector addSector(String sectorName, BigDecimal price) {
        Sector sector = new Sector(this, sectorName, price);
        sectors.add(sector);
        return sector;
    }

    /**
     * Sales (holds and checkout) close when the event starts.
     */
    public boolean isOnSaleAt(Instant now) {
        return now.isBefore(startsAt);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public List<Sector> getSectors() {
        return Collections.unmodifiableList(sectors);
    }
}
