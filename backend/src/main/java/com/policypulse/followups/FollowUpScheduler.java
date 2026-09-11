package com.policypulse.followups;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the follow-up engine hourly, on the half hour.
 *
 * <p>Hourly for the same reason reminders are: tenants keep their own timezones,
 * so their days begin at different moments and a sweep has to come round often
 * enough to catch each one. On the half hour so it does not contend with the
 * reminder sweep for the same rows.
 */
@Component
@ConditionalOnProperty(name = "app.follow-ups.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class FollowUpScheduler {
    private final FollowUpRunner runner;

    public FollowUpScheduler(FollowUpRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${app.follow-ups.cron:0 30 * * * *}")
    public void workThroughFollowUps() {
        runner.runAll();
    }
}
