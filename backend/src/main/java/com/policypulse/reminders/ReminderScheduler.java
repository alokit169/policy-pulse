package com.policypulse.reminders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs detection hourly rather than once a day.
 *
 * <p>Each tenant has its own timezone, so their days roll over at different
 * moments; an hourly sweep picks each one up shortly after midnight local time.
 * Running often is safe because detection is idempotent, so the extra runs find
 * nothing new rather than duplicating work.
 */
@Component
@ConditionalOnProperty(name = "app.reminders.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {
    private final ReminderRunner runner;

    public ReminderScheduler(ReminderRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${app.reminders.cron:0 0 * * * *}")
    public void detectDueReminders() {
        runner.runAll();
    }
}
