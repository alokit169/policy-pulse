package com.policypulse.reminders;

import com.policypulse.common.Domain;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record ReminderConfigurationRequest(
        /**
         * Comma separated days before a premium falls due, such as "10,5,1,0".
         * Written with character classes rather than shorthands so the pattern
         * needs no escaping.
         */
        @Pattern(regexp = "^ *[0-9]{1,3} *(, *[0-9]{1,3} *)*$",
                message = "Days before due must be a comma separated list of day counts")
        @Size(max = 64, message = "Days before due is too long")
        String daysBeforeDue,

        @Pattern(regexp = "^ *[0-9]{1,3} *(, *[0-9]{1,3} *)*$",
                message = "Days after due must be a comma separated list of day counts")
        @Size(max = 64, message = "Days after due is too long")
        String daysAfterDue,

        @Min(value = 1, message = "At least one attempt must be allowed")
        @Max(value = 10, message = "At most ten attempts may be allowed")
        int maxCallAttempts,

        @Min(value = 1, message = "Retry delay must be at least a minute")
        @Max(value = 1440, message = "Retry delay cannot exceed a day")
        int retryDelayMinutes,

        @NotNull(message = "Calling window start is required")
        LocalTime allowedCallingStart,

        @NotNull(message = "Calling window end is required")
        LocalTime allowedCallingEnd,

        @NotNull(message = "Preferred channel is required")
        Domain.Channel preferredChannel) {
}
