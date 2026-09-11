package com.policypulse.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A message for one user inside the application. Addressed to a user rather than
 * a tenant, but still carries the organization so a listing can be scoped
 * without joining through users.
 */
@Entity
@Table(name = "in_app_notifications")
public class InAppNotification {
    @Id
    private UUID id;

    private UUID organizationId;
    private UUID userId;
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "read")
    private boolean read;

    private Instant createdAt;

    protected InAppNotification() {
        // for JPA
    }

    InAppNotification(UUID organizationId, UUID userId, String title, String body) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.title = title;
        this.body = body;
    }

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public boolean isRead() { return read; }
    public void markRead() { this.read = true; }
    public Instant getCreatedAt() { return createdAt; }
}
