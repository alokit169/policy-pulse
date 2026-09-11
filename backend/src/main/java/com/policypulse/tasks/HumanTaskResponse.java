package com.policypulse.tasks;

import com.policypulse.common.Domain;

import java.time.Instant;
import java.util.UUID;

public record HumanTaskResponse(
        UUID id,
        UUID customerId,
        UUID policyId,
        UUID conversationId,
        UUID assignedAgentId,
        Domain.HumanTaskPriority priority,
        String reason,
        Domain.HumanTaskStatus status,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt) {

    public static HumanTaskResponse of(HumanTask t) {
        return new HumanTaskResponse(
                t.getId(), t.getCustomerId(), t.getPolicyId(), t.getConversationId(),
                t.getAssignedAgentId(), t.getPriority(), t.getReason(), t.getStatus(),
                t.getDueAt(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
