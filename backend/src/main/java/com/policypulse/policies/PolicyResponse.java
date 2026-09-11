package com.policypulse.policies;

import com.policypulse.common.Domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PolicyResponse(
        UUID id,
        UUID customerId,
        UUID agentId,
        String policyNumber,
        String insuranceProvider,
        String policyType,
        String planName,
        String currencyCode,
        BigDecimal sumAssured,
        BigDecimal premiumAmount,
        Domain.PremiumFrequency premiumFrequency,
        LocalDate policyStartDate,
        LocalDate policyEndDate,
        LocalDate maturityDate,
        LocalDate nextPremiumDueDate,
        LocalDate lastPremiumPaidDate,
        Domain.PolicyStatus status,
        String nomineeName,
        BigDecimal bonusAmount,
        BigDecimal maturityAmount,
        Instant createdAt,
        Instant updatedAt) {

    public static PolicyResponse of(Policy p) {
        return new PolicyResponse(
                p.getId(),
                p.getCustomerId(),
                p.getAgentId(),
                p.getPolicyNumber(),
                p.getInsuranceProvider(),
                p.getPolicyType(),
                p.getPlanName(),
                p.getCurrencyCode(),
                p.getSumAssured(),
                p.getPremiumAmount(),
                p.getPremiumFrequency(),
                p.getPolicyStartDate(),
                p.getPolicyEndDate(),
                p.getMaturityDate(),
                p.getNextPremiumDueDate(),
                p.getLastPremiumPaidDate(),
                p.getStatus(),
                p.getNomineeName(),
                p.getBonusAmount(),
                p.getMaturityAmount(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
