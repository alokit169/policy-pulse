package com.policypulse.conversations;

import com.policypulse.common.Domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID customerId,
        UUID policyId,
        UUID agentId,
        UUID reminderId,
        Domain.Channel channel,
        Domain.ConversationDirection direction,
        Domain.ConversationStatus status,
        Instant startedAt,
        Instant endedAt,
        String summary,
        String sentiment,
        String outcome,
        Integer durationSeconds,
        Instant createdAt,
        /** Present on a single conversation, omitted from listings. */
        List<MessageResponse> messages) {

    public static ConversationResponse of(Conversation c, List<MessageResponse> messages) {
        return new ConversationResponse(
                c.getId(), c.getCustomerId(), c.getPolicyId(), c.getAgentId(), c.getReminderId(),
                c.getChannel(), c.getDirection(), c.getStatus(), c.getStartedAt(), c.getEndedAt(),
                c.getSummary(), c.getSentiment(), c.getOutcome(), c.getDurationSeconds(),
                c.getCreatedAt(), messages);
    }

    public static ConversationResponse summary(Conversation c) {
        return of(c, null);
    }
}
