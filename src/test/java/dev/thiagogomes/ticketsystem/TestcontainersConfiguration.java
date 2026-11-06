package dev.thiagogomes.ticketsystem;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers are static so every Spring context (one per reservation strategy) shares the same instances.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return REDIS;
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return RABBIT;
    }
}
