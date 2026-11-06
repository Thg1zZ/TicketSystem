package dev.thiagogomes.ticketsystem.shared;

import dev.thiagogomes.ticketsystem.shared.error.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Minimal protection for admin-only operations until real authentication exists (out of scope).
 * Fails closed: with no token configured, every admin call is refused.
 */
@Component
public class AdminTokenGuard {

    private final byte[] expectedToken;

    public AdminTokenGuard(@Value("${ticket.admin.token:}") String configuredToken) {
        this.expectedToken = configuredToken.isBlank() ? null : configuredToken.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Constant-time comparison: the response time does not reveal how many leading characters matched.
     */
    public void check(String providedToken) {
        boolean valid = expectedToken != null
                && providedToken != null
                && MessageDigest.isEqual(expectedToken, providedToken.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new UnauthorizedException("Missing or invalid admin token");
        }
    }
}
