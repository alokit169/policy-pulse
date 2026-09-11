package com.policypulse.tasks;

import com.policypulse.common.Domain;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Work that a person has to do, because the system will not do it itself.
 *
 * <p>Most of these come from the assistant reaching the edge of what it is
 * allowed to act on: a payment it believes was made, a request to speak to
 * someone, or an answer it was not confident about.
 */
@Entity
@Table(name = "human_tasks")
public class HumanTask {
    @Id
    private UUID id;

    private UUID organizationId;
    private UUID customerId;
    private UUID policyId;
    private UUID conversationId;
    private UUID assignedAgentId;

    @Enumerated(EnumType.STRING)
    private Domain.HumanTaskPriority priority;

    private String reason;

    @Enumerated(EnumType.STRING)
    private Domain.HumanTaskStatus status;

    private Instant dueAt;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (priority == null) priority = Domain.HumanTaskPriority.MEDIUM;
        if (status == null) status = Domain.HumanTaskStatus.OPEN;
    }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public UUID getAssignedAgentId() { return assignedAgentId; }
    public void setAssignedAgentId(UUID assignedAgentId) { this.assignedAgentId = assignedAgentId; }
    public Domain.HumanTaskPriority getPriority() { return priority; }
    public void setPriority(Domain.HumanTaskPriority priority) { this.priority = priority; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Domain.HumanTaskStatus getStatus() { return status; }
    public void setStatus(Domain.HumanTaskStatus status) { this.status = status; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
