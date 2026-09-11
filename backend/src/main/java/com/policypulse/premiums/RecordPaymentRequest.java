package com.policypulse.premiums;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Records that a premium was actually collected. The amount is not taken from
 * the caller: it is whatever the instalment says, so recording a payment can
 * never quietly change what was owed.
 */
public record RecordPaymentRequest(
        /** Defaults to today when omitted. A future date is rejected. */
        LocalDate paidDate,

        @Size(max = 128, message = "Payment reference must be at most 128 characters")
        String paymentReference,

        @Size(max = 64, message = "Payment method must be at most 64 characters")
        String paymentMethod) {
}
