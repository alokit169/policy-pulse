package com.policypulse.messaging;

import com.policypulse.common.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Sends no email. Reports what a provider would have said, so the rest of the
 * system can be exercised end to end without writing to a real person.
 *
 * <p>Deterministic on the address rather than random: a demo shows a mix of
 * outcomes and a test that picks an address gets the same one every time. An
 * address at {@code invalid.test} bounces and one at {@code fail.test} errors;
 * everything else is accepted.
 */
@Component
@ConditionalOnProperty(name = "app.notification.provider", havingValue = "mock", matchIfMissing = true)
public class MockEmailProvider implements MessageProvider {
    private static final Logger log = LoggerFactory.getLogger(MockEmailProvider.class);

    @Override
    public Domain.Channel channel() {
        return Domain.Channel.EMAIL;
    }

    @Override
    public String name() {
        return "mock-email";
    }

    @Override
    public SendResult send(OutboundMessage message) {
        log.info("Mock email to {} (attempt {})", Addresses.mask(message.address()), message.attempt());

        String reference = "mock-email-" + UUID.randomUUID();
        String address = message.address() == null ? "" : message.address().toLowerCase();

        if (address.endsWith("@invalid.test")) {
            return SendResult.of(SendResult.Outcome.INVALID_ADDRESS, reference);
        }
        if (address.endsWith("@fail.test")) {
            return SendResult.of(SendResult.Outcome.FAILED, reference);
        }
        return SendResult.of(SendResult.Outcome.ACCEPTED, reference);
    }
}
