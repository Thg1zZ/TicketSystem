package dev.thiagogomes.ticketsystem.queue;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Admission pass: {@code base64url(eventId.userId.expiresAtMs).base64url(HMAC-SHA256)}. Stateless: any replica
 * verifies a pass issued by another one with the shared secret, without asking Redis.
 */
final class QueuePasses {

    static final int MIN_SECRET_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    QueuePasses(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("The queue pass secret must have at least %d bytes".formatted(MIN_SECRET_BYTES));
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    String issue(long eventId, String userId, Instant expiresAt) {
        String payload = ENCODER.encodeToString("%d.%s.%d".formatted(eventId, userId, expiresAt.toEpochMilli())
                .getBytes(StandardCharsets.UTF_8));
        return payload + "." + ENCODER.encodeToString(sign(payload));
    }

    /**
     * Valid only for this event and user and before it expires; anything malformed is simply invalid.
     */
    boolean isValid(String pass, long eventId, String userId, Instant now) {
        int dot = pass == null ? -1 : pass.indexOf('.');
        if (dot < 0) {
            return false;
        }
        try {
            String payload = pass.substring(0, dot);
            if (!MessageDigest.isEqual(sign(payload), DECODER.decode(pass.substring(dot + 1)))) { // constant time
                return false;
            }
            String[] fields = new String(DECODER.decode(payload), StandardCharsets.UTF_8).split("\\.");
            return fields.length == 3
                    && fields[0].equals(Long.toString(eventId))
                    && fields[1].equals(userId)
                    && now.toEpochMilli() < Long.parseLong(fields[2]);
        } catch (IllegalArgumentException malformed) { // bad base64 or number
            return false;
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", e);
        }
    }
}
