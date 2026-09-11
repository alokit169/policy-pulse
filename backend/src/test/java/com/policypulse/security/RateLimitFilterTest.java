package com.policypulse.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit test: no Spring context, so it stays fast.
 */
class RateLimitFilterTest {

    private static final int LIMIT = 3;

    private MockHttpServletResponse callFrom(RateLimitFilter filter, String clientIp)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/anything");
        request.setRemoteAddr(clientIp);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsRequestsUpToTheLimitThenRejects() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(LIMIT);

        for (int i = 1; i <= LIMIT; i++) {
            assertThat(callFrom(filter, "10.0.0.1").getStatus())
                    .as("request %d of %d should be allowed", i, LIMIT)
                    .isEqualTo(200);
        }

        assertThat(callFrom(filter, "10.0.0.1").getStatus()).isEqualTo(429);
    }

    /**
     * Guards the bug where nginx forwarded no client headers, so every user
     * behind the proxy shared a single bucket.
     */
    @Test
    void clientsAreCountedIndependently() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(LIMIT);

        for (int i = 1; i <= LIMIT; i++) {
            callFrom(filter, "10.0.0.1");
        }
        assertThat(callFrom(filter, "10.0.0.1").getStatus()).isEqualTo(429);

        assertThat(callFrom(filter, "10.0.0.2").getStatus())
                .as("a different client must not inherit the first client's count")
                .isEqualTo(200);
        assertThat(filter.trackedClientCount()).isEqualTo(2);
    }

    @Test
    void evictionKeepsWindowsForTheCurrentMinute() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(LIMIT);
        callFrom(filter, "10.0.0.1");

        filter.evictStaleWindows();

        assertThat(filter.trackedClientCount())
                .as("an active client must not be dropped mid-window")
                .isEqualTo(1);
    }
}
