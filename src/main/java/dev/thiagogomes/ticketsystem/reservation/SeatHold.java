package dev.thiagogomes.ticketsystem.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seat_hold")
public class SeatHold {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HoldStatus status = HoldStatus.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected SeatHold() {
    }

    public SeatHold(Long eventId, String userId, Instant expiresAt) {
        this.eventId = eventId;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public boolean isActiveAt(Instant now) {
        return status == HoldStatus.ACTIVE && expiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getUserId() {
        return userId;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
