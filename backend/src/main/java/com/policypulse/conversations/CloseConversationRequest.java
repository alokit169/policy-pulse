package com.policypulse.conversations;

import com.policypulse.common.Domain;
import jakarta.validation.constraints.Size;

/** How a conversation ended. All fields optional; omitted ones keep their value. */
public record CloseConversationRequest(
        /** Defaults to COMPLETED. FAILED or ESCALATED record a call that did not land. */
        Domain.ConversationStatus status,

        @Size(max = 64, message = "Outcome must be at most 64 characters")
        String outcome,

        @Size(max = 32, message = "Sentiment must be at most 32 characters")
        String sentiment,

        String summary) {
}
