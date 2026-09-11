package com.policypulse.reminders;

import com.policypulse.common.Domain;

import java.time.Instant;
import java.util.UUID;

public record ReminderResponse(
        UUID id,
        UUID customerId,
        UUID policyId,
        Domain.ReminderType reminderType,
        Instant scheduledAt,
        Domain.Channel channel,
        Domain.ReminderStatus status,
        int attemptCount,
        Instant lastAttemptAt,
        Instant nextAttemptAt,
        Instant createdAt) {

    public static ReminderResponse of(Reminder r) {
        return new ReminderResponse(
                r.getId(), r.getCustomerId(), r.getPolicyId(), r.getReminderType(),
                r.getScheduledAt(), r.getChannel(), r.getStatus(), r.getAttemptCount(),
                r.getLastAttemptAt(), r.getNextAttemptAt(), r.getCreatedAt());
    }
}
