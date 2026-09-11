package com.policypulse.config;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.security.JwtAuthFilter;
import com.policypulse.security.RateLimitFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot registers every Filter bean in the servlet chain automatically.
 * Both of these are also placed explicitly in the security chain, so without a
 * disabled registration they run at the servlet chain's position rather than
 * where SecurityConfig puts them.
 */
class FilterRegistrationTest extends AbstractIntegrationTest {

    @Autowired private ApplicationContext context;

    private void assertNotAutoRegistered(Class<? extends Filter> filterType) {
        var registrations = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .toList();

        assertThat(registrations)
                .as("expected a FilterRegistrationBean for %s", filterType.getSimpleName())
                .isNotEmpty();

        assertThat(registrations)
                .as("%s must not be auto-registered in the servlet chain", filterType.getSimpleName())
                .allSatisfy(registration -> assertThat(registration.isEnabled()).isFalse());
    }

    @Test
    void securityFiltersAreNotAlsoRegisteredInTheServletChain() {
        assertNotAutoRegistered(JwtAuthFilter.class);
        assertNotAutoRegistered(RateLimitFilter.class);
    }
}
