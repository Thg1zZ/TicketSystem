package dev.thiagogomes.ticketsystem.event;

import com.jayway.jsonpath.JsonPath;
import dev.thiagogomes.ticketsystem.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class EventControllerTest {

    private static final String VALID_EVENT = """
            {
              "name": "Show de Abertura",
              "venue": "Arena Central",
              "startsAt": "2030-01-15T20:00:00Z",
              "sectors": [
                { "name": "Pista", "price": 150.00, "rows": 2, "seatsPerRow": 5 },
                { "name": "VIP",   "price": 400.00, "rows": 1, "seatsPerRow": 4 }
              ]
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @Test
    void createsEventAndReturnsLocation() throws Exception {
        postEvent(VALID_EVENT)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/events/")))
                .andExpect(jsonPath("$.name").value("Show de Abertura"))
                .andExpect(jsonPath("$.sectors", hasSize(2)))
                .andExpect(jsonPath("$.sectors[1].price").value(400.00));
    }

    @Test
    void seatMapListsEverySeatAsAvailable() throws Exception {
        long eventId = createEvent();

        mockMvc.perform(get("/events/{id}/seats", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.sectors[0].name").value("Pista"))
                .andExpect(jsonPath("$.sectors[0].available").value(10))
                .andExpect(jsonPath("$.sectors[0].seats", hasSize(10)))
                .andExpect(jsonPath("$.sectors[0].seats[0].row").value("A"))
                .andExpect(jsonPath("$.sectors[0].seats[0].number").value(1))
                .andExpect(jsonPath("$.sectors[1].available").value(4))
                .andExpect(jsonPath("$.sectors[*].seats[*].status", everyItem(is("AVAILABLE"))));
    }

    @Test
    void getsEventById() throws Exception {
        long eventId = createEvent();

        mockMvc.perform(get("/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venue").value("Arena Central"));
    }

    @Test
    void unknownEventReturns404Problem() throws Exception {
        mockMvc.perform(get("/events/{id}/seats", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("Event 999999 not found"));
    }

    @Test
    void invalidPayloadReturns400WithFieldErrors() throws Exception {
        String invalid = """
                { "name": "", "venue": "Arena", "startsAt": "2000-01-01T00:00:00Z",
                  "sectors": [ { "name": "Pista", "price": 0, "rows": 30, "seatsPerRow": 5 } ] }
                """;

        postEvent(invalid)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.startsAt").exists())
                .andExpect(jsonPath("$.errors['sectors[0].price']").exists())
                .andExpect(jsonPath("$.errors['sectors[0].rows']").exists());
    }

    @Test
    void duplicatedSectorNamesReturn422() throws Exception {
        String duplicated = """
                { "name": "Show", "venue": "Arena", "startsAt": "2030-01-01T00:00:00Z",
                  "sectors": [ { "name": "Pista", "price": 10, "rows": 1, "seatsPerRow": 1 },
                               { "name": "pista", "price": 20, "rows": 1, "seatsPerRow": 1 } ] }
                """;

        postEvent(duplicated)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Business rule violated"));
    }

    @Test
    void creatingEventsRequiresTheAdminToken() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/events").header("X-Admin-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void eventCapacityIsCapped() throws Exception {
        String tooBig = """
                { "name": "Huge", "venue": "Arena", "startsAt": "2030-01-01T00:00:00Z",
                  "sectors": [ { "name": "A", "price": 10, "rows": 26, "seatsPerRow": 100 },
                               { "name": "B", "price": 10, "rows": 26, "seatsPerRow": 100 },
                               { "name": "C", "price": 10, "rows": 26, "seatsPerRow": 100 },
                               { "name": "D", "price": 10, "rows": 26, "seatsPerRow": 100 } ] }
                """;

        postEvent(tooBig)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("An event can have at most 10000 seats, got 10400"));
    }

    @Test
    void priceAboveTheCeilingIsRejected() throws Exception {
        String expensive = """
                { "name": "VIP", "venue": "Arena", "startsAt": "2030-01-01T00:00:00Z",
                  "sectors": [ { "name": "A", "price": 1000000.00, "rows": 1, "seatsPerRow": 2 } ] }
                """;

        postEvent(expensive)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['sectors[0].price']").exists());
    }

    private ResultActions postEvent(String json) throws Exception {
        return mockMvc.perform(post("/events")
                .header("X-Admin-Token", IntegrationTest.ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private long createEvent() throws Exception {
        String body = postEvent(VALID_EVENT)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
