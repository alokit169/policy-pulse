package com.policypulse.reminders;

import com.policypulse.common.Domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reminders")
public class Reminder {
    @Id
    private UUID id;
    private UUID organizationId;
    private UUID customerId;
    private UUID policyId;
    /** The instalment this is about. Null on reminders raised before it was recorded. */
    private UUID premiumPaymentId;
    @Enumerated(EnumType.STRING)
    private Domain.ReminderType reminderType;
    private Instant scheduledAt;
    @Enumerated(EnumType.STRING)
    private Domain.Channel channel;
    @Enumerated(EnumType.STRING)
    private Domain.ReminderStatus status;
    private int attemptCount;
    private Instant lastAttemptAt;
    private Instant nextAttemptAt;
    private String idempotencyKey;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = Domain.ReminderStatus.PENDING;
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
    public UUID getPremiumPaymentId() { return premiumPaymentId; }
    public void setPremiumPaymentId(UUID premiumPaymentId) { this.premiumPaymentId = premiumPaymentId; }
    public Domain.ReminderType getReminderType() { return reminderType; }
    public void setReminderType(Domain.ReminderType reminderType) { this.reminderType = reminderType; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public Domain.Channel getChannel() { return channel; }
    public void setChannel(Domain.Channel channel) { this.channel = channel; }
    public Domain.ReminderStatus getStatus() { return status; }
    public void setStatus(Domain.ReminderStatus status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
