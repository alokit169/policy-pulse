package com.policypulse.common;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ErrorHandlingTest extends AbstractIntegrationTest {

    /**
     * Regression: the catch-all Exception handler used to swallow
     * NoResourceFoundException and report every unmatched route as 500.
     *
     * <p>Authenticated deliberately. Security rejects an anonymous request before
     * dispatch, so an anonymous call would return 401 no matter how the handler
     * behaves and would pass even if the bug came back.
     */
    @Test
    void unmatchedRouteReturns404NotServerError() throws Exception {
        AppUser user = createActiveAgent();
        String token = tokenFor(user);

        mvc.perform(get("/api/no-such-endpoint").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void protectedRouteWithoutTokenAsksForAuthentication() throws Exception {
        mvc.perform(get("/api/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocsArePublic() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }
}
