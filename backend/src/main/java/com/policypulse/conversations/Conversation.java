package com.policypulse.conversations;

import com.policypulse.common.Domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation {
    @Id
    private UUID id;
    private UUID organizationId;
    private UUID customerId;
    private UUID policyId;
    private UUID agentId;
    private UUID reminderId;
    @Enumerated(EnumType.STRING)
    private Domain.Channel channel;
    @Enumerated(EnumType.STRING)
    private Domain.ConversationDirection direction;
    @Enumerated(EnumType.STRING)
    private Domain.ConversationStatus status;
    private Instant startedAt;
    private Instant endedAt;
    @Column(columnDefinition = "TEXT")
    private String summary;
    private String sentiment;
    private String outcome;
    private Integer durationSeconds;
    private Instant createdAt;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (startedAt == null) startedAt = now;
        if (status == null) status = Domain.ConversationStatus.STARTED;
        if (direction == null) direction = Domain.ConversationDirection.OUTBOUND;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }
    public UUID getReminderId() { return reminderId; }
    public void setReminderId(UUID reminderId) { this.reminderId = reminderId; }
    public Domain.Channel getChannel() { return channel; }
    public void setChannel(Domain.Channel channel) { this.channel = channel; }
    public Domain.ConversationDirection getDirection() { return direction; }
    public void setDirection(Domain.ConversationDirection direction) { this.direction = direction; }
    public Domain.ConversationStatus getStatus() { return status; }
    public void setStatus(Domain.ConversationStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
