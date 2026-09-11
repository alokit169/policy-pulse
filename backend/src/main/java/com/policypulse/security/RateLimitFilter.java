package com.policypulse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final int limit;

    /**
     * Per-client counters. Keyed by remote address, which is the real client IP
     * only because ForwardedHeaderFilter is enabled (server.forward-headers-strategy)
     * and the proxy sets X-Forwarded-For. Without that every request behind the
     * proxy shares one bucket.
     */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.rate-limit.requests-per-minute}") int limit) {
        this.limit = limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr();
        Window w = windows.compute(key, (k, existing) -> {
            long minute = Instant.now().getEpochSecond() / 60;
            if (existing == null || existing.minute != minute) {
                return new Window(minute, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        if (w.count.get() > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Entries for clients that stop sending requests would otherwise be retained
     * forever, so sweep windows that are no longer current.
     */
    @Scheduled(fixedDelay = 60_000)
    void evictStaleWindows() {
        long currentMinute = Instant.now().getEpochSecond() / 60;
        windows.entrySet().removeIf(entry -> entry.getValue().minute() < currentMinute);
    }

    int trackedClientCount() {
        return windows.size();
    }

    private record Window(long minute, AtomicInteger count) {}
}
