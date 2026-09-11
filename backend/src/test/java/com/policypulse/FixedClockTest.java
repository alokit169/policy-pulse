package com.policypulse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the override itself. If the application's own clock bean won instead,
 * every date-sensitive test would quietly be running against the real date.
 */
class FixedClockTest extends AbstractIntegrationTest {

    @Autowired private Clock clock;

    @Test
    void theSuiteRunsAgainstAFrozenClock() {
        assertThat(Instant.now(clock)).isEqualTo(FixedClockConfiguration.FIXED_NOW);
    }
}
