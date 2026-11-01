package nyc.tracknyc.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import nyc.tracknyc.config.TrackNycProperties;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client-IP token bucket on {@code /api/**} (the SSE stream is limited by the gateway's
 * connection cap instead). Answers 429 with Retry-After when a client exceeds its budget.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final TrackNycProperties.RateLimit cfg;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(TrackNycProperties.RateLimit cfg) {
        this.cfg = cfg;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !cfg.isEnabled() || !uri.startsWith("/api/") || uri.startsWith("/api/stream");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.nanoTime();
        if (buckets.size() > 50_000) {
            buckets.entrySet().removeIf(e -> e.getValue().isFull(now)); // forget idle clients
        }
        TokenBucket bucket = buckets.computeIfAbsent(clientKey(request),
                k -> new TokenBucket(cfg.getRequestsPerSecond(), cfg.getBurst(), now));
        if (!bucket.tryAcquire(now)) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(Math.max(1, bucket.secondsUntilToken())));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    String clientKey(HttpServletRequest request) {
        if (cfg.isTrustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
