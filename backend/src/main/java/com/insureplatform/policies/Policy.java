package com.insureplatform.policies;

import com.insureplatform.common.Domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "policies")
public class Policy {
    @Id
    private UUID id;
    private UUID organizationId;
    private UUID customerId;
    private UUID agentId;
    private String policyNumber;
    private String insuranceProvider;
    private String policyType;
    private String planName;
    private String currencyCode;
    private BigDecimal sumAssured;
    private BigDecimal premiumAmount;
    @Enumerated(EnumType.STRING)
    private Domain.PremiumFrequency premiumFrequency;
    private LocalDate policyStartDate;
    private LocalDate policyEndDate;
    private LocalDate maturityDate;
    private LocalDate nextPremiumDueDate;
    private LocalDate lastPremiumPaidDate;
    @Enumerated(EnumType.STRING)
    private Domain.PolicyStatus status;
    private String nomineeName;
    private BigDecimal bonusAmount;
    private BigDecimal maturityAmount;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (currencyCode == null) currencyCode = "INR";
        if (status == null) status = Domain.PolicyStatus.ACTIVE;
    }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public String getInsuranceProvider() { return insuranceProvider; }
    public void setInsuranceProvider(String insuranceProvider) { this.insuranceProvider = insuranceProvider; }
    public String getPolicyType() { return policyType; }
    public void setPolicyType(String policyType) { this.policyType = policyType; }
    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public BigDecimal getSumAssured() { return sumAssured; }
    public void setSumAssured(BigDecimal sumAssured) { this.sumAssured = sumAssured; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public void setPremiumAmount(BigDecimal premiumAmount) { this.premiumAmount = premiumAmount; }
    public Domain.PremiumFrequency getPremiumFrequency() { return premiumFrequency; }
    public void setPremiumFrequency(Domain.PremiumFrequency premiumFrequency) { this.premiumFrequency = premiumFrequency; }
    public LocalDate getPolicyStartDate() { return policyStartDate; }
    public void setPolicyStartDate(LocalDate policyStartDate) { this.policyStartDate = policyStartDate; }
    public LocalDate getPolicyEndDate() { return policyEndDate; }
    public void setPolicyEndDate(LocalDate policyEndDate) { this.policyEndDate = policyEndDate; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }
    public LocalDate getNextPremiumDueDate() { return nextPremiumDueDate; }
    public void setNextPremiumDueDate(LocalDate nextPremiumDueDate) { this.nextPremiumDueDate = nextPremiumDueDate; }
    public LocalDate getLastPremiumPaidDate() { return lastPremiumPaidDate; }
    public void setLastPremiumPaidDate(LocalDate lastPremiumPaidDate) { this.lastPremiumPaidDate = lastPremiumPaidDate; }
    public Domain.PolicyStatus getStatus() { return status; }
    public void setStatus(Domain.PolicyStatus status) { this.status = status; }
    public String getNomineeName() { return nomineeName; }
    public void setNomineeName(String nomineeName) { this.nomineeName = nomineeName; }
    public BigDecimal getBonusAmount() { return bonusAmount; }
    public void setBonusAmount(BigDecimal bonusAmount) { this.bonusAmount = bonusAmount; }
    public BigDecimal getMaturityAmount() { return maturityAmount; }
    public void setMaturityAmount(BigDecimal maturityAmount) { this.maturityAmount = maturityAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
