package com.policypulse.messaging;

/**
 * How a send went.
 *
 * <p>A provider accepting a message is not the same as a customer reading it.
 * ACCEPTED means it was handed over, nothing more; delivery receipts arrive later
 * and over a webhook, which is work a real provider brings with it.
 */
public record SendResult(Outcome outcome, String providerReference) {

    public enum Outcome {
        /** The provider took it. */
        ACCEPTED,
        /** The address is not usable. Sending again will not help. */
        INVALID_ADDRESS,
        /** The provider refused this message: too long, blocked, over quota. */
        REJECTED,
        /** Something went wrong at the provider. Worth trying again. */
        FAILED;

        /** Whether another attempt could plausibly do better. */
        public boolean worthRetrying() {
            return this == FAILED;
        }
    }

    public static SendResult of(Outcome outcome, String reference) {
        return new SendResult(outcome, reference);
    }
}
