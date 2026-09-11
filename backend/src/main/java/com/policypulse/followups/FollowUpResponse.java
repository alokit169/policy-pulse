package com.policypulse.followups;

import com.policypulse.common.Domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FollowUpResponse(
        UUID id,
        UUID customerId,
        UUID policyId,
        UUID conversationId,
        UUID assignedAgentId,
        String reason,
        LocalDate commitmentDate,
        Instant dueAt,
        Domain.FollowUpStatus status,
        String notes,
        /** Set once a broken promise has been handed to a person. */
        Instant escalatedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static FollowUpResponse of(FollowUp f) {
        return new FollowUpResponse(
                f.getId(), f.getCustomerId(), f.getPolicyId(), f.getConversationId(),
                f.getAssignedAgentId(), f.getReason(), f.getCommitmentDate(), f.getDueAt(),
                f.getStatus(), f.getNotes(), f.getEscalatedAt(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
