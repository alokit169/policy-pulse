package com.policypulse.premiums;

import com.policypulse.common.Domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "premium_payments")
public class PremiumPayment {
    @Id
    private UUID id;
    private UUID organizationId;
    private UUID policyId;
    private BigDecimal amount;
    private LocalDate dueDate;
    private LocalDate paidDate;
    @Enumerated(EnumType.STRING)
    private Domain.PremiumStatus status;
    private String paymentReference;
    private String paymentMethod;
    private boolean verificationPending;
    private Instant createdAt;

    /**
     * Guards the read-check-write in PremiumService.recordPayment. Only the
     * instalment carries a version: the policy's cached premium dates are derived
     * from its instalments and recomputed on every change, so a lost update there
     * corrects itself, and versioning the policy would reject two agents settling
     * different instalments of the same policy at the same moment.
     */
    @Version
    private long version;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate paidDate) { this.paidDate = paidDate; }
    public Domain.PremiumStatus getStatus() { return status; }
    public void setStatus(Domain.PremiumStatus status) { this.status = status; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public boolean isVerificationPending() { return verificationPending; }
    public void setVerificationPending(boolean verificationPending) { this.verificationPending = verificationPending; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public long getVersion() { return version; }
}
