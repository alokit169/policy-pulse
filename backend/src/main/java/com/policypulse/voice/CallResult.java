package com.policypulse.voice;

import java.util.List;

/**
 * How a call went.
 *
 * <p>A transcript is a record of what was said, not a decision about it. Reading
 * meaning into it is the assistant's job, and acting on that meaning is the
 * validation layer's.
 */
public record CallResult(
        Outcome outcome,
        int durationSeconds,
        /** The provider's own reference, so a call can be traced back to them. */
        String providerReference,
        List<Line> transcript) {

    public enum Outcome {
        /** Someone picked up and spoke. */
        ANSWERED,
        /** Rang out. Worth trying again. */
        NO_ANSWER,
        /** Engaged. Worth trying again. */
        BUSY,
        /** The number is not usable. Trying again will not help. */
        INVALID_NUMBER,
        /** Something went wrong at the provider. Worth trying again. */
        FAILED;

        /** Whether another attempt could plausibly do better. */
        public boolean worthRetrying() {
            return this == NO_ANSWER || this == BUSY || this == FAILED;
        }
    }

    /** One line, attributed. Who said it decides what it may mean later. */
    public record Line(String sender, String text) {
    }

    public static CallResult of(Outcome outcome, String reference) {
        return new CallResult(outcome, 0, reference, List.of());
    }
}
