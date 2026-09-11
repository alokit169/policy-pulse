package com.policypulse.ai;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Makes the stub the provider the application uses during tests. The real
 * rule-based provider is still tested, on its own, without Spring.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubAiConfiguration {

    @Bean
    @Primary
    public StubAIProvider stubAIProvider() {
        return new StubAIProvider();
    }
}
