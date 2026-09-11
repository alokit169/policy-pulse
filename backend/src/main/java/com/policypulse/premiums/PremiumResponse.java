package com.policypulse.premiums;

import com.policypulse.common.Domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PremiumResponse(
        UUID id,
        UUID policyId,
        BigDecimal amount,
        LocalDate dueDate,
        LocalDate paidDate,
        Domain.PremiumStatus status,
        String paymentReference,
        String paymentMethod,
        boolean verificationPending,
        Instant createdAt) {

    public static PremiumResponse of(PremiumPayment p) {
        return new PremiumResponse(
                p.getId(),
                p.getPolicyId(),
                p.getAmount(),
                p.getDueDate(),
                p.getPaidDate(),
                p.getStatus(),
                p.getPaymentReference(),
                p.getPaymentMethod(),
                p.isVerificationPending(),
                p.getCreatedAt());
    }
}
