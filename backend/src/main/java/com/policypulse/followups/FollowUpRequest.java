package com.policypulse.followups;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record FollowUpRequest(
        @NotNull(message = "Customer is required")
        UUID customerId,

        UUID policyId,

        /** Optional: links the follow-up to the conversation it came out of. */
        UUID conversationId,

        @NotNull(message = "Reason is required")
        FollowUpReason reason,

        /**
         * The day the customer named. Required for a payment commitment, since
         * that is the whole content of the promise.
         */
        LocalDate commitmentDate,

        String notes) {
}
