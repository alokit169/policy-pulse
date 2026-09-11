package com.policypulse.voice;

/**
 * Places a call and reports what happened.
 *
 * <p>Whether a call is allowed at all is decided before this is reached:
 * consent, the tenant's calling window and how many times the customer has
 * already been tried are not a provider's concern, and a provider must never be
 * the thing that enforces them.
 */
public interface VoiceProvider {

    /** A name for logs and for the conversation record, so a call can be traced. */
    String name();

    CallResult call(CallRequest request);
}
