package dev.thiagogomes.ticketsystem.event;

import dev.thiagogomes.ticketsystem.shared.error.ServiceUnavailableException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live seat maps over Server-Sent Events. One loop per instance re-reads the (cached) snapshot of each watched
 * event and pushes it only when its ETag changed, so the database cost depends on how many events are watched,
 * not on how many people watch them.
 */
// ponytail: pushes the whole map on every change; send per-seat diffs when maps get large (10k seats ~ 400 KB)
@Component
class SeatMapStreams {

    private static final Logger log = LoggerFactory.getLogger(SeatMapStreams.class);
    static final String SEATS_EVENT = "seats";

    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, String> lastPushed = new ConcurrentHashMap<>();
    private final AtomicInteger openStreams = new AtomicInteger();
    // A slow client must not delay the others: every send runs on its own virtual thread
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
    private final SeatMapCache cache;
    private final JsonMapper json;
    private final int maxStreams;
    private final Duration streamTimeout;

    SeatMapStreams(SeatMapCache cache, JsonMapper json,
                   @Value("${ticket.seat-map.max-streams}") int maxStreams,
                   @Value("${ticket.seat-map.stream-timeout}") Duration streamTimeout,
                   MeterRegistry meters) {
        this.cache = cache;
        this.json = json;
        this.maxStreams = maxStreams;
        this.streamTimeout = streamTimeout;
        Gauge.builder("seatmap.streams", openStreams, AtomicInteger::get).register(meters);
    }

    SseEmitter subscribe(Long eventId) {
        SeatMapSnapshot snapshot = cache.get(eventId); // unknown event: 404 before opening the stream
        if (openStreams.incrementAndGet() > maxStreams) {
            openStreams.decrementAndGet();
            throw new ServiceUnavailableException(
                    "Too many live seat map connections; poll GET /events/%d/seats instead".formatted(eventId));
        }
        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
        Set<SseEmitter> watchers = subscribers.computeIfAbsent(eventId, id -> ConcurrentHashMap.newKeySet());
        watchers.add(emitter);
        Runnable unsubscribe = () -> {
            if (watchers.remove(emitter)) {
                openStreams.decrementAndGet();
            }
        };
        emitter.onCompletion(unsubscribe);
        emitter.onError(error -> unsubscribe.run());
        // Without complete() a timeout becomes a 503 ProblemDetail that cannot be written to an event stream;
        // EventSource simply reconnects
        emitter.onTimeout(emitter::complete);
        lastPushed.putIfAbsent(eventId, snapshot.etag()); // first watcher: the loop must not resend what it just got
        send(emitter, seatsEvent(snapshot));
        return emitter;
    }

    @Scheduled(fixedDelayString = "${ticket.seat-map.push-interval}")
    void pushChanges() {
        subscribers.forEach((eventId, watchers) -> {
            if (watchers.isEmpty()) {
                lastPushed.remove(eventId);
                return;
            }
            try {
                SeatMapSnapshot snapshot = cache.get(eventId);
                if (!snapshot.etag().equals(lastPushed.put(eventId, snapshot.etag()))) {
                    broadcast(watchers, seatsEvent(snapshot));
                }
            } catch (RuntimeException e) {
                // Database hiccup: clients keep the last map and the next round retries
                log.warn("Could not refresh the live seat map of event {}: {}", eventId, e.getMessage());
            }
        });
        cache.evictIdle();
    }

    /**
     * Keeps idle connections alive through proxies (nginx closes a silent upstream after 60 s) and finds the
     * dead ones: a closed client is only noticed when a write fails, so without traffic it would keep its slot.
     */
    @Scheduled(fixedRateString = "${ticket.seat-map.heartbeat-interval}")
    void heartbeat() {
        Set<DataWithMediaType> ping = SseEmitter.event().comment("ping").build();
        subscribers.values().forEach(watchers -> broadcast(watchers, ping));
    }

    int openStreams() {
        return openStreams.get();
    }

    /**
     * Open streams would otherwise hold graceful shutdown for its whole timeout. ContextClosedEvent is published
     * before the web server starts draining requests.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        subscribers.values().forEach(watchers -> watchers.forEach(SseEmitter::complete));
        sender.shutdown();
    }

    private Set<DataWithMediaType> seatsEvent(SeatMapSnapshot snapshot) {
        // Serialized once per change, not once per watcher
        String map = json.writeValueAsString(snapshot.map());
        return SseEmitter.event().id(snapshot.etag()).name(SEATS_EVENT).data(map).build();
    }

    private void broadcast(Set<SseEmitter> watchers, Set<DataWithMediaType> event) {
        watchers.forEach(emitter -> sender.execute(() -> send(emitter, event)));
    }

    private static void send(SseEmitter emitter, Set<DataWithMediaType> event) {
        try {
            emitter.send(event);
        } catch (Exception gone) {
            emitter.completeWithError(gone); // the client left; triggers unsubscribe
        }
    }
}
