package com.policypulse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    /**
     * Injected rather than called statically so date-sensitive logic, such as
     * whether a premium is overdue, can be tested at a fixed instant.
     *
     * <p>Organizations carry their own timezone. Evaluating each tenant in its
     * own zone matters for call windows and belongs with the reminder scheduler;
     * until then a single configured zone decides what "today" means.
     */
    @Bean
    public Clock clock(@Value("${app.timezone:UTC}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
