package com.policypulse.conversations;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One line of a conversation. */
public record MessageRequest(
        @NotNull(message = "Sender is required")
        ConversationMessageSender sender,

        @NotBlank(message = "Message is required")
        @Size(max = 10_000, message = "Message is too long")
        String message,

        @Size(max = 255, message = "Transcript reference must be at most 255 characters")
        String transcriptReference) {
}
