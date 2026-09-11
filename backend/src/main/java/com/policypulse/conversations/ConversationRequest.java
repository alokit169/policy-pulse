package com.policypulse.conversations;

import com.policypulse.common.Domain;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Logs an interaction with a customer. Recorded by a person in this phase;
 * automated calls fill in the same record later.
 */
public record ConversationRequest(
        @NotNull(message = "Customer is required")
        UUID customerId,

        /** Optional: a conversation may be general rather than about one policy. */
        UUID policyId,

        @NotNull(message = "Channel is required")
        Domain.Channel channel,

        @NotNull(message = "Direction is required")
        Domain.ConversationDirection direction,

        @Size(max = 64, message = "Outcome must be at most 64 characters")
        String outcome,

        @Size(max = 32, message = "Sentiment must be at most 32 characters")
        String sentiment,

        String summary) {
}
