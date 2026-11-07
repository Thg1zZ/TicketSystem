package dev.thiagogomes.ticketsystem.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Baseline response headers (OWASP Secure Headers). no-store matters here: responses carry ticket codes,
 * which are what the gate trusts, so no browser or proxy cache may keep them.
 * Runs first, so responses written by other filters (413, 429) carry the headers too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * Only this origin's own files run: an injected script or a third-party resource is refused by the browser.
     * QR codes are shown from blob: URLs; inline styles are allowed for the Swagger UI.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' data: blob:; "
            + "style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
            + "form-action 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        chain.doFilter(request, response);
    }
}
