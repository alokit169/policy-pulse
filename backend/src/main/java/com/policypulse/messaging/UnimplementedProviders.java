package com.policypulse.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands where real email and SMS will go, selected with
 * {@code app.notification.provider=real}.
 *
 * <p>Not implemented, and it refuses to start rather than failing per message.
 * Sending nothing while reporting success is the failure that would be noticed
 * last: reminders would keep being marked as sent and no customer would ever hear
 * from anybody. Failing per message is barely better — every send would throw and
 * roll back, taking the attempt count with it, so nothing would even run out of
 * attempts.
 *
 * <p>What a real implementation has to deal with, which the mocks do not:
 *
 * <ul>
 *   <li><b>Accepted is not delivered.</b> A provider taking a message says
 *       nothing about it arriving. Bounces, and for SMS delivery receipts, come
 *       back later on a webhook — a public endpoint that must verify the
 *       provider's signature and be replay-safe.</li>
 *   <li><b>A hard bounce is a fact about the address.</b> It has to be recorded
 *       against the customer, or every future reminder repeats the same failure
 *       and the sending reputation pays for it.</li>
 *   <li><b>Unsubscribe is a legal obligation, not a feature.</b> The rules for
 *       marketing differ from those for transactional messages, and a premium
 *       reminder is only transactional while it stays about the premium.</li>
 *   <li><b>SMS is charged per segment</b>, so a body that grows by one character
 *       can cost twice as much to send.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.notification.provider", havingValue = "real")
public class UnimplementedProviders {
    private static final Logger log = LoggerFactory.getLogger(UnimplementedProviders.class);

    public UnimplementedProviders(@Value("${app.notification.api-key:}") String apiKey) {
        log.error("app.notification.provider=real is selected but no real provider is implemented");
        if (apiKey.isBlank()) {
            log.error("app.notification.api-key is also unset");
        }
        throw new IllegalStateException(
                "app.notification.provider=real is not implemented. Set app.notification.provider=mock.");
    }
}
