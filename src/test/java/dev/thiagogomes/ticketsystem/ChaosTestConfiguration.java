package dev.thiagogomes.ticketsystem;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A stack of its own (not the shared test containers): every connection of this context goes through Toxiproxy,
 * so a test can cut or slow down Postgres, Redis or RabbitMQ. Own containers also matter for correctness: the
 * other cached contexts would otherwise keep publishing this context's outbox through their healthy connections.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ChaosTestConfiguration {

    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"))
            .withNetwork(NETWORK).withNetworkAliases("postgres");
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis@sha256:71885d8cc6e42f2bc0aa2eb853bfc4be200b5108a46ac50e117ae1df0531fb80"))
            .withNetwork(NETWORK).withNetworkAliases("redis");
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq@sha256:6cb708876a6163c93f1e5af03a649db13765027ff7a7d775c01a63b6a80545ee"))
            .withNetwork(NETWORK).withNetworkAliases("rabbitmq");
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy@sha256:3866784ec3527e3a425780d73c763f6133bf3a034ddba64254e903d445d3668f")
                    .asCompatibleSubstituteFor("ghcr.io/shopify/toxiproxy"))
            .withNetwork(NETWORK);

    public static final Proxy POSTGRES_PROXY;
    public static final Proxy REDIS_PROXY;
    public static final Proxy RABBIT_PROXY;

    static {
        Startables.deepStart(POSTGRES, REDIS, RABBIT, TOXIPROXY).join();
        try {
            ToxiproxyClient toxiproxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            POSTGRES_PROXY = toxiproxy.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
            REDIS_PROXY = toxiproxy.createProxy("redis", "0.0.0.0:8667", "redis:6379");
            RABBIT_PROXY = toxiproxy.createProxy("rabbitmq", "0.0.0.0:8668", "rabbitmq:5672");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    DynamicPropertyRegistrar chaosConnections() {
        String host = TOXIPROXY.getHost();
        return registry -> {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://%s:%d/%s"
                    .formatted(host, TOXIPROXY.getMappedPort(8666), POSTGRES.getDatabaseName()));
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.host", () -> host);
            registry.add("spring.data.redis.port", () -> TOXIPROXY.getMappedPort(8667));
            registry.add("spring.data.redis.password", () -> "");
            registry.add("spring.rabbitmq.host", () -> host);
            registry.add("spring.rabbitmq.port", () -> TOXIPROXY.getMappedPort(8668));
            registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
            registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        };
    }
}
