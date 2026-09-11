package com.policypulse.voice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a provider needs to place one call, and nothing more.
 *
 * <p>No identifiers: a provider is an outside service, and it needs a number to
 * dial and enough to hold a conversation, not the records behind them.
 *
 * @param phoneNumber   the number to dial
 * @param customerName  first name only, so the call can open naturally
 * @param premiumDueOn  what the call is about, if it is about a premium
 * @param amountDue     the figure the policy says, never one a caller supplies
 * @param attempt       which try this is, so a provider can vary its opening
 */
public record CallRequest(
        String phoneNumber,
        String customerName,
        LocalDate premiumDueOn,
        BigDecimal amountDue,
        int attempt) {
}
