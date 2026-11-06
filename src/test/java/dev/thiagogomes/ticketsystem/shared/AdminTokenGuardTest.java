package dev.thiagogomes.ticketsystem.shared;

import dev.thiagogomes.ticketsystem.shared.error.UnauthorizedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AdminTokenGuardTest {

    @Test
    void acceptsOnlyTheConfiguredToken() {
        AdminTokenGuard guard = new AdminTokenGuard("s3cret-token");

        assertThatCode(() -> guard.check("s3cret-token")).doesNotThrowAnyException();
        assertThatExceptionOfType(UnauthorizedException.class).isThrownBy(() -> guard.check("s3cret-tokeN"));
        assertThatExceptionOfType(UnauthorizedException.class).isThrownBy(() -> guard.check(null));
    }

    @Test
    void failsClosedWhenNoTokenIsConfigured() {
        AdminTokenGuard guard = new AdminTokenGuard("  ");

        assertThatExceptionOfType(UnauthorizedException.class).isThrownBy(() -> guard.check(""));
        assertThatExceptionOfType(UnauthorizedException.class).isThrownBy(() -> guard.check("  "));
    }
}
