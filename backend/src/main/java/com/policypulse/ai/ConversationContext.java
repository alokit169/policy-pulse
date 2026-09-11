package com.policypulse.ai;

import java.time.LocalDate;
import java.util.List;

/**
 * What a model is told about a call, and nothing more.
 *
 * <p>No identifiers and no contact details: a model needs to know that a premium
 * is overdue to make sense of "I will pay on Friday", not who the customer is or
 * how to reach them. Keeping those out means a logged prompt cannot leak them.
 *
 * @param today        the tenant's date, so a relative day can be resolved
 * @param customerName first name only, for a natural reply
 * @param premiumDueOn when the next unsettled instalment falls due, if any
 * @param transcript   the lines, in order
 */
public record ConversationContext(
        LocalDate today,
        String customerName,
        LocalDate premiumDueOn,
        List<Line> transcript) {

    /** One line, attributed. Who said it decides what it can mean. */
    public record Line(String sender, String text) {
    }
}
