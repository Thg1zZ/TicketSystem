package dev.thiagogomes.ticketsystem.queue;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * OPEN: the queue is off, holds need no pass. WAITING: position and an estimate. ADMITTED: send {@code pass}
 * as X-Queue-Pass when holding seats, until {@code passExpiresAt}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueStatusResponse(Status status, Long position, Long estimatedWaitSeconds, String pass,
                                  Instant passExpiresAt) {

    public enum Status { OPEN, WAITING, ADMITTED }

    static QueueStatusResponse open() {
        return new QueueStatusResponse(Status.OPEN, null, null, null, null);
    }

    static QueueStatusResponse waiting(long position, long estimatedWaitSeconds) {
        return new QueueStatusResponse(Status.WAITING, position, estimatedWaitSeconds, null, null);
    }

    static QueueStatusResponse admitted(String pass, Instant expiresAt) {
        return new QueueStatusResponse(Status.ADMITTED, null, null, pass, expiresAt);
    }
}
