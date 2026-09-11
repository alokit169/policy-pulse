package com.policypulse.policies;

import com.policypulse.common.Domain;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Create and update payload. The organization is taken from the caller, never
 * from the request.
 *
 * <p>Money is BigDecimal throughout and constrained to the two decimal places
 * the columns store, so a value that cannot be represented is rejected rather
 * than silently rounded on write.
 */
public record PolicyRequest(
        @NotNull(message = "Customer is required")
        UUID customerId,

        @Size(max = 64, message = "Policy number must be at most 64 characters")
        /*
         * Bounded in shape as well as length. A policy number is whatever the
         * agency types and it ends up in an email subject, where a line break
         * would end the header and let the rest become headers of its own. The
         * message layer strips control characters too; this stops them being
         * stored at all.
         */
        @Pattern(regexp = "^[A-Za-z0-9 ._/-]*$",
                message = "Policy number may contain only letters, digits, spaces and . _ / -")
        String policyNumber,

        @NotBlank(message = "Insurance provider is required")
        @Size(max = 120, message = "Insurance provider must be at most 120 characters")
        String insuranceProvider,

        @NotBlank(message = "Policy type is required")
        @Size(max = 64, message = "Policy type must be at most 64 characters")
        String policyType,

        @Size(max = 200, message = "Plan name must be at most 200 characters")
        String planName,

        /**
         * Rupees only. Kept in the payload and validated rather than silently
         * ignored, so a client sending anything else is told why. The database
         * carries the same constraint.
         */
        @Pattern(regexp = "^INR$", message = "Only INR is supported")
        String currencyCode,

        @DecimalMin(value = "0.00", message = "Sum assured cannot be negative")
        @Digits(integer = 16, fraction = 2, message = "Sum assured supports at most 2 decimal places")
        BigDecimal sumAssured,

        @NotNull(message = "Premium amount is required")
        @DecimalMin(value = "0.01", message = "Premium amount must be greater than zero")
        @Digits(integer = 16, fraction = 2, message = "Premium amount supports at most 2 decimal places")
        BigDecimal premiumAmount,

        @NotNull(message = "Premium frequency is required")
        Domain.PremiumFrequency premiumFrequency,

        LocalDate policyStartDate,
        LocalDate policyEndDate,
        LocalDate maturityDate,

        @Size(max = 200, message = "Nominee name must be at most 200 characters")
        String nomineeName,

        @DecimalMin(value = "0.00", message = "Bonus cannot be negative")
        @Digits(integer = 16, fraction = 2, message = "Bonus supports at most 2 decimal places")
        BigDecimal bonusAmount,

        @DecimalMin(value = "0.00", message = "Maturity amount cannot be negative")
        @Digits(integer = 16, fraction = 2, message = "Maturity amount supports at most 2 decimal places")
        BigDecimal maturityAmount,

        /**
         * Optional. Agents always hold their own policies, so this is ignored for
         * them. Managers and admins may assign within their own organization.
         */
        UUID agentId) {
}
