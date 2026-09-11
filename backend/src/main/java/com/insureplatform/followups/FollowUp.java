package com.insureplatform.followups;

import com.insureplatform.common.Domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "follow_ups")
public class FollowUp {
    @Id
    private UUID id;
    private UUID organizationId;
    private UUID customerId;
    private UUID policyId;
    private UUID conversationId;
    private UUID assignedAgentId;
    private String reason;
    private LocalDate commitmentDate;
    private Instant dueAt;
    @Enumerated(EnumType.STRING)
    private Domain.FollowUpStatus status;
    @Column(columnDefinition = "TEXT")
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = Domain.FollowUpStatus.OPEN;
    }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
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
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDate getCommitmentDate() { return commitmentDate; }
    public void setCommitmentDate(LocalDate commitmentDate) { this.commitmentDate = commitmentDate; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Domain.FollowUpStatus getStatus() { return status; }
    public void setStatus(Domain.FollowUpStatus status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
