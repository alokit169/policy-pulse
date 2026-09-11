package com.policypulse.voice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Makes the stub the provider the application uses during tests. */
@TestConfiguration(proxyBeanMethods = false)
public class StubVoiceConfiguration {

    @Bean
    @Primary
    public StubVoiceProvider stubVoiceProvider() {
        return new StubVoiceProvider();
    }
}
