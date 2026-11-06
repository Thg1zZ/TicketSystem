package dev.thiagogomes.ticketsystem.event;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import dev.thiagogomes.ticketsystem.event.EventFixtures.CreatedEvent;
import dev.thiagogomes.ticketsystem.reservation.HoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class SeatMapReadTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EventService eventService;
    @Autowired
    HoldService holdService;

    CreatedEvent event;

    @BeforeEach
    void createEvent() {
        event = EventFixtures.createEvent(eventService, 3);
    }

    @Test
    void unchangedMapAnswers304WithoutBodyAndChangesAfterAHold() throws Exception {
        String etag = mockMvc.perform(get("/events/{id}/seats", event.id()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();

        mockMvc.perform(get("/events/{id}/seats", event.id()).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(emptyString()));

        holdService.create(event.id(), "watcher-test", List.of(event.seatIds().getFirst()));

        mockMvc.perform(get("/events/{id}/seats", event.id()).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, org.hamcrest.Matchers.not(etag)));
    }

    @Test
    void liveStreamSendsTheMapOnConnectAndAgainWhenASeatChanges() throws Exception {
        MockHttpServletResponse stream = mockMvc.perform(get("/events/{id}/seats/stream", event.id())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn().getResponse();

        await().atMost(Duration.ofSeconds(5)).until(() -> stream.getContentAsString().contains("event:seats"));
        assertThat(stream.getContentAsString()).doesNotContain("HELD");

        holdService.create(event.id(), "live-test", List.of(event.seatIds().getFirst()));

        await().atMost(Duration.ofSeconds(5)).until(() -> stream.getContentAsString().contains("\"status\":\"HELD\""));
    }

    @Test
    void streamOfUnknownEventIs404() throws Exception {
        mockMvc.perform(get("/events/{id}/seats/stream", 999_999)).andExpect(status().isNotFound());
    }
}
