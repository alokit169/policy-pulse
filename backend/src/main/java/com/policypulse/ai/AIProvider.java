package com.policypulse.ai;

/**
 * Reads a conversation and says what it appears to mean.
 *
 * <p>An implementation is a source of opinion, not of authority. Nothing it
 * returns is written anywhere without passing through
 * {@link ActionValidationService} first, so a provider that is wrong, slow, or
 * hostile can waste a call but cannot change what a customer owes.
 */
public interface AIProvider {

    /** A name for logs and for the analysis record, so a decision can be traced back. */
    String name();

    IntentAnalysis analyse(ConversationContext context);
}
