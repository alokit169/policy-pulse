package com.policypulse.reminders;

import com.policypulse.common.Domain;

import java.time.LocalTime;

public record ReminderConfigurationResponse(
        String daysBeforeDue,
        String daysAfterDue,
        int maxCallAttempts,
        int retryDelayMinutes,
        LocalTime allowedCallingStart,
        LocalTime allowedCallingEnd,
        Domain.Channel preferredChannel,
        /** The tenant's timezone, which decides what "today" means for due dates. */
        String timezone) {

    public static ReminderConfigurationResponse of(ReminderConfiguration c, String timezone) {
        return new ReminderConfigurationResponse(
                c.getDaysBeforeDue(), c.getDaysAfterDue(), c.getMaxCallAttempts(), c.getRetryDelayMinutes(),
                c.getAllowedCallingStart(), c.getAllowedCallingEnd(), c.getPreferredChannel(), timezone);
    }
}
