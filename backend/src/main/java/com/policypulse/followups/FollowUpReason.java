package com.policypulse.followups;

/**
 * Why someone has to come back to this.
 *
 * <p>A closed set rather than free text, because the follow-up engine and the
 * reports both group by it, and free text cannot be grouped.
 */
public enum FollowUpReason {
    /** The customer said they would pay, on a date they gave. */
    PAYMENT_COMMITMENT,
    CALLBACK_REQUESTED,
    DOCUMENT_PROMISED,
    COMPLAINT,
    OTHER
}
