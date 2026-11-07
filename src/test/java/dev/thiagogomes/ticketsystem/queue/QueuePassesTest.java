package dev.thiagogomes.ticketsystem.queue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueuePassesTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant EXPIRES = NOW.plus(Duration.ofMinutes(15));

    private final QueuePasses passes = new QueuePasses("a-test-secret-with-at-least-32-bytes!");
    private final String pass = passes.issue(7, "alice", EXPIRES);

    @Test
    void validForItsEventAndUserUntilItExpires() {
        assertThat(passes.isValid(pass, 7, "alice", NOW)).isTrue();
        assertThat(passes.isValid(pass, 7, "alice", EXPIRES)).isFalse();
    }

    @Test
    void notTransferableToAnotherUserOrEvent() {
        assertThat(passes.isValid(pass, 7, "bob", NOW)).isFalse();
        assertThat(passes.isValid(pass, 8, "alice", NOW)).isFalse();
    }

    @Test
    void anyTamperingInvalidatesIt() {
        String payload = pass.substring(0, pass.indexOf('.'));
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("7.alice.9999999999999".getBytes());

        assertThat(passes.isValid(forgedPayload + pass.substring(pass.indexOf('.')), 7, "alice", NOW)).isFalse();
        assertThat(passes.isValid(payload + ".AAAA", 7, "alice", NOW)).isFalse();
        assertThat(new QueuePasses("another-secret-with-at-least-32-bytes").isValid(pass, 7, "alice", NOW)).isFalse();
    }

    @Test
    void malformedPassesAreInvalidNotErrors() {
        assertThat(passes.isValid(null, 7, "alice", NOW)).isFalse();
        assertThat(passes.isValid("no-dot", 7, "alice", NOW)).isFalse();
        assertThat(passes.isValid("!!!.???", 7, "alice", NOW)).isFalse();
    }

    @Test
    void weakSecretsAreRefused() {
        assertThatThrownBy(() -> new QueuePasses("short")).isInstanceOf(IllegalStateException.class);
    }
}
