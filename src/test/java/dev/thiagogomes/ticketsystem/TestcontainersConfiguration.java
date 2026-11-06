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

    // Same images and digests as docker-compose.yml (postgres:17-alpine, redis:8-alpine, rabbitmq:4-management-alpine).
    // Digest-only form: Testcontainers checks compatibility by repository name and rejects "tag@digest".
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"));
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis@sha256:71885d8cc6e42f2bc0aa2eb853bfc4be200b5108a46ac50e117ae1df0531fb80")).withExposedPorts(6379);
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq@sha256:6cb708876a6163c93f1e5af03a649db13765027ff7a7d775c01a63b6a80545ee"));

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
