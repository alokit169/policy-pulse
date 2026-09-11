package com.policypulse.messaging;

import com.policypulse.common.Domain;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Makes the stubs the providers the application uses during tests.
 *
 * <p>The registry collects every MessageProvider on the classpath rather than
 * injecting one, so marking a stub primary would not displace the mock. The test
 * profile selects neither mock instead: {@code app.notification.provider=stub}
 * leaves both mocks unloaded and only these registered.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubMessagingConfiguration {

    @Bean
    public StubMessageProvider stubEmailProvider() {
        return new StubMessageProvider(Domain.Channel.EMAIL);
    }

    @Bean
    public StubMessageProvider stubSmsProvider() {
        return new StubMessageProvider(Domain.Channel.SMS);
    }
}
