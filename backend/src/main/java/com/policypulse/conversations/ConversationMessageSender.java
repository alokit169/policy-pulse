package com.policypulse.conversations;

/**
 * Who said a line.
 *
 * <p>A closed set rather than free text, because later phases decide what to act
 * on by who said it: a commitment is only a commitment when the customer made
 * it, and nothing the assistant says may be treated as one.
 */
public enum ConversationMessageSender {
    AGENT,
    CUSTOMER,
    SYSTEM,
    ASSISTANT
}
