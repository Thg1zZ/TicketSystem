package dev.thiagogomes.ticketsystem.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Every payload of this API is a few hundred bytes; bigger bodies are refused before anything parses them.
 * A declared Content-Length over the limit gets 413; a chunked body is cut off while being read (ends as 400).
 */
@Component
class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    RequestBodyLimitFilter(@Value("${ticket.http.max-request-body}") String maxRequestBody) {
        this.maxBytes = DataSize.parse(maxRequestBody).toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Content Too Large","status":413,"detail":"Request body exceeds %d bytes"}"""
                    .formatted(maxBytes));
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request, maxBytes), response);
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        BoundedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream body = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int value = body.read();
                    if (value != -1) {
                        count(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int chunk = body.read(buffer, offset, length);
                    if (chunk > 0) {
                        count(chunk);
                    }
                    return chunk;
                }

                private void count(int bytes) throws IOException {
                    read += bytes;
                    if (read > maxBytes) {
                        throw new IOException("Request body exceeds " + maxBytes + " bytes");
                    }
                }

                @Override
                public boolean isFinished() {
                    return body.isFinished();
                }

                @Override
                public boolean isReady() {
                    return body.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    body.setReadListener(listener);
                }
            };
        }
    }
}
