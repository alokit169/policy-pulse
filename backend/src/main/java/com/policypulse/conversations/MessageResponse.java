package com.policypulse.conversations;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        String sender,
        String message,
        Instant timestamp,
        String transcriptReference) {

    public static MessageResponse of(ConversationMessage m) {
        return new MessageResponse(
                m.getId(), m.getSender(), m.getMessage(), m.getTimestamp(), m.getTranscriptReference());
    }
}
