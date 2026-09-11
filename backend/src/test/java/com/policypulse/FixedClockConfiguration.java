package com.policypulse;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Freezes time for the whole suite.
 *
 * <p>Reminder detection decides what is due from "today", and in each tenant's
 * own timezone. Against a moving clock those tests would be correct almost
 * always and wrong for a few seconds around midnight, which is the worst kind of
 * flake.
 *
 * <p>Marked primary and given its own bean name rather than relying on
 * definition overriding, which depends on registration order: when the
 * application's bean won instead, the whole suite ran against the real date and
 * said nothing. FixedClockTest guards that.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    /** Mid-morning UTC, so tenants either side of it sit on different dates. */
    public static final Instant FIXED_NOW = Instant.parse("2026-09-15T06:00:00Z");

    @Bean
    @Primary
    public Clock fixedTestClock() {
        return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
}
