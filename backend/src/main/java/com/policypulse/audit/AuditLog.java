package com.policypulse.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a security- or business-relevant action. Rows are never
 * updated or deleted, so there are no setters beyond construction.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {
    @Id
    private UUID id;

    /** Null for events that occur before a tenant is known, such as a failed login. */
    private UUID organizationId;

    /** Null when the actor could not be identified. */
    private UUID actorId;

    private String actorEmail;
    private String action;
    private String entity;
    private String entityId;

    @Column(name = "timestamp")
    private Instant timestamp;

    private String metadata;

    protected AuditLog() {
        // for JPA
    }

    AuditLog(UUID organizationId, UUID actorId, String actorEmail,
             String action, String entity, String entityId, String metadata) {
        this.organizationId = organizationId;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.metadata = metadata;
    }

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        if (timestamp == null) timestamp = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getEntity() { return entity; }
    public String getEntityId() { return entityId; }
    public Instant getTimestamp() { return timestamp; }
    public String getMetadata() { return metadata; }
}
