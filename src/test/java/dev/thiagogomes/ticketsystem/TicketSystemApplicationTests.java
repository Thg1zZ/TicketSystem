package dev.thiagogomes.ticketsystem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class TicketSystemApplicationTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthShowsComponentStatusButNoInternals() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"))
                .andExpect(jsonPath("$.components.rabbit.status").value("UP"))
                .andExpect(jsonPath("$.components.db.details").doesNotExist())
                .andExpect(jsonPath("$.components.diskSpace.details").doesNotExist());
    }

    @Test
    void probesAreServedOnTheMainPort() throws Exception {
        mockMvc.perform(get("/livez")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis").doesNotExist());
    }

    @Test
    void metricsAreNotExposedByDefault() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/events/1"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void oversizedBodyIsRejectedBeforeParsing() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("x".repeat(70_000)))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void demoPageIsServedAtTheRoot() throws Exception {
        mockMvc.perform(get("/")).andExpect(forwardedUrl("index.html"));
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TicketSystem")));
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/app.css")).andExpect(status().isOk());
    }

    @Test
    void unknownRouteReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").exists());
    }

    @Test
    void openApiDocsAreExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Ticket System API"))
                .andExpect(jsonPath("$.paths['/holds/{holdId}/checkout'].post.responses['409']").exists())
                .andExpect(jsonPath("$.components.schemas.Problem").exists());
    }
}
