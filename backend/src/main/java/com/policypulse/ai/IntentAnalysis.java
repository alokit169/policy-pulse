package com.policypulse.ai;

import com.policypulse.common.Domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a model made of a conversation. Untrusted input, not a decision.
 *
 * <p>Deliberately carries no identifiers. A model is never asked which customer,
 * policy or instalment it is talking about, and could not be believed if it
 * answered: the system already knows, from the conversation record the transcript
 * came from. Everything here is a reading of what was said, and the validation
 * layer decides what, if anything, may be written because of it.
 *
 * @param intent        what the customer appeared to mean
 * @param confidence    0 to 1; below the acting threshold nothing is written
 *                      except work for a person
 * @param committedDate a date the customer named, if any
 * @param statedAmount  an amount the customer mentioned. A claim, never used to
 *                      change what is owed, which is what the policy says
 * @param summary       a short account of the call, for people to read
 * @param sentiment     how the call felt, for reporting only
 */
public record IntentAnalysis(
        Domain.AiIntent intent,
        double confidence,
        LocalDate committedDate,
        BigDecimal statedAmount,
        String summary,
        String sentiment) {

    public static IntentAnalysis unknown(String summary) {
        return new IntentAnalysis(Domain.AiIntent.UNKNOWN, 0.0, null, null, summary, null);
    }
}
