package com.policypulse.users;

import com.policypulse.common.Domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class AppUser {
    @Id
    private UUID id;
    private UUID organizationId;
    private UUID managerId;
    private String name;
    private String email;
    private String phone;
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    private Domain.Role role;
    @Enumerated(EnumType.STRING)
    private Domain.EntityStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;

    /**
     * Raised to invalidate every token already issued to this user. Tokens carry
     * the value they were minted with and are rejected once it falls behind.
     */
    private int tokenVersion;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = Domain.EntityStatus.ACTIVE;
    }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getManagerId() { return managerId; }
    public void setManagerId(UUID managerId) { this.managerId = managerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Domain.Role getRole() { return role; }
    public void setRole(Domain.Role role) { this.role = role; }
    public Domain.EntityStatus getStatus() { return status; }
    public void setStatus(Domain.EntityStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }
    /** Invalidates every token already issued to this user. */
    public void revokeIssuedTokens() { this.tokenVersion++; }
}
