package dev.thiagogomes.ticketsystem.shared;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@OpenAPIDefinition(info = @Info(
        title = "Ticket System API",
        version = "v1",
        description = "Venda de ingressos com alta concorrência. Rotas de usuário exigem X-User-Id; criar eventos exige X-Admin-Token."))
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String PROBLEM_SCHEMA = "Problem";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final Map<String, String> STANDARD_ERRORS = Map.of(
            "400", "Invalid request (field errors listed in 'errors')",
            "404", "Not found, also returned for resources that belong to another user",
            "429", "Too many requests from this user or IP; retry after Retry-After seconds",
            "409", "Conflict with the current state (seat taken, hold not active...)",
            "422", "Business rule violated (sales closed, repeated seats...)",
            "503", "A dependency is temporarily unavailable; retry after Retry-After seconds");

    /**
     * Every error is an RFC 9457 problem; documenting it once here keeps the controllers free of annotations.
     */
    @Bean
    OpenApiCustomizer standardErrorResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
            Content problem = new Content().addMediaType(PROBLEM_JSON,
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)));

            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                    STANDARD_ERRORS.forEach((status, description) -> operation.getResponses()
                            .putIfAbsent(status, new ApiResponse().description(description).content(problem)))));
        };
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .addProperty("type", new StringSchema())
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema())
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema());
    }
}
