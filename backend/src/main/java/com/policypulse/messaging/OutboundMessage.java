package com.policypulse.messaging;

import com.policypulse.common.Domain;

/**
 * What a provider needs to send one message, and nothing more.
 *
 * <p>No identifiers. A provider is an outside service: it needs somewhere to send
 * to and something to say, not the records behind them. The subject is ignored by
 * channels that have none.
 *
 * @param channel   which provider this is for
 * @param address   the email address or phone number to send to
 * @param subject   the subject line, for channels that carry one
 * @param body      what to say, already written for the customer to read
 * @param attempt   which try this is
 */
public record OutboundMessage(
        Domain.Channel channel,
        String address,
        String subject,
        String body,
        int attempt) {
}
