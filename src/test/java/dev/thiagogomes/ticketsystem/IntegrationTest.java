package dev.thiagogomes.ticketsystem;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full context against real Postgres, Redis and RabbitMQ. Same config = Spring reuses the context and containers.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "ticket.payment.min-latency=0s",
        "ticket.payment.max-latency=0s",
        "ticket.payment.failure-rate=0",
        "ticket.admin.token=" + IntegrationTest.ADMIN_TOKEN,
        "ticket.outbox.relay-interval=PT0.1S",
        // Readers right after a write must see it; concurrent reads are still coalesced (single-flight)
        "ticket.seat-map.cache-ttl=0s",
        "ticket.seat-map.push-interval=PT0.1S",
        // Tests fire many writes from one IP and user; AdmissionControlTest turns both features on
        "ticket.rate-limit.enabled=false",
        "ticket.queue.enabled=false",
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {

    String ADMIN_TOKEN = "test-admin-token";
}
