package dev.thiagogomes.ticketsystem.shared.web;

import dev.thiagogomes.ticketsystem.shared.RedisCircuit;
import dev.thiagogomes.ticketsystem.shared.RequestHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Token buckets in Redis for every write (POST, PUT, PATCH, DELETE), per IP and per user, shared by all
 * replicas: over the limit answers 429 with Retry-After (OWASP API4/API6 against floods and scripted buyers).
 * The client IP comes from X-Forwarded-For, which nginx overwrites, so a client cannot choose its own.
 * Reads are not limited: they are cheap and cached.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET = RedisScript.of(new ClassPathResource("redis/token-bucket.lua"), List.class);
    private static final Pattern VALID_USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final StringRedisTemplate redis;
    private final RedisCircuit redisCircuit;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final Limit perIp;
    private final Limit perUser;

    RateLimitFilter(StringRedisTemplate redis, RedisCircuit redisCircuit, MeterRegistry meters,
                    @Value("${ticket.rate-limit.enabled}") boolean enabled,
                    @Value("${ticket.rate-limit.per-ip.capacity}") int ipCapacity,
                    @Value("${ticket.rate-limit.per-ip.refill-per-second}") double ipRefill,
                    @Value("${ticket.rate-limit.per-user.capacity}") int userCapacity,
                    @Value("${ticket.rate-limit.per-user.refill-per-second}") double userRefill) {
        this.redis = redis;
        this.redisCircuit = redisCircuit;
        this.meters = meters;
        this.enabled = enabled;
        this.perIp = new Limit("ip", ipCapacity, ipRefill);
        this.perUser = new Limit("user", userCapacity, userRefill);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !enabled || "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfterMs = take(perIp, request.getRemoteAddr());
        String userId = request.getHeader(RequestHeaders.USER_ID);
        if (retryAfterMs == 0 && userId != null && VALID_USER_ID.matcher(userId).matches()) {
            retryAfterMs = take(perUser, userId);
        }
        if (retryAfterMs > 0) {
            tooManyRequests(response, retryAfterMs);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Fails open without Redis: the limiter protects capacity, it is not what keeps sales correct.
     *
     * @return 0 when allowed, otherwise milliseconds until the next token
     */
    @SuppressWarnings("unchecked")
    private long take(Limit limit, String subject) {
        List<Long> result = redisCircuit.callOrElse("rate-limit", () -> redis.execute(TOKEN_BUCKET,
                        List.of("ratelimit:" + limit.scope() + ":" + subject),
                        String.valueOf(limit.capacity()), String.valueOf(limit.refillPerSecond())),
                () -> List.of(1L, 0L));
        if (result.getFirst() == 1) {
            return 0;
        }
        meters.counter("ratelimit.rejected", "scope", limit.scope()).increment();
        return Math.max(1, result.get(1));
    }

    private static void tooManyRequests(HttpServletResponse response, long retryAfterMs) throws IOException {
        long seconds = Math.ceilDiv(retryAfterMs, 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Request limit reached; retry in %d s"}"""
                .formatted(seconds));
    }

    private record Limit(String scope, int capacity, double refillPerSecond) {
        Limit {
            if (capacity < 1 || refillPerSecond <= 0) {
                throw new IllegalStateException("Invalid rate limit for " + scope);
            }
        }
    }
}
