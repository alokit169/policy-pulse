package com.policypulse.messaging;

import com.policypulse.common.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Sends no SMS. Deterministic on the last digit of the number, the same way the
 * mock voice provider is, so one demo number behaves consistently across both:
 * {@code 2} is unusable, {@code 3} fails at the provider, anything else is taken.
 */
@Component
@ConditionalOnProperty(name = "app.notification.provider", havingValue = "mock", matchIfMissing = true)
public class MockSmsProvider implements MessageProvider {
    private static final Logger log = LoggerFactory.getLogger(MockSmsProvider.class);

    @Override
    public Domain.Channel channel() {
        return Domain.Channel.SMS;
    }

    @Override
    public String name() {
        return "mock-sms";
    }

    @Override
    public SendResult send(OutboundMessage message) {
        log.info("Mock SMS to {} (attempt {})", Addresses.mask(message.address()), message.attempt());

        String reference = "mock-sms-" + UUID.randomUUID();
        return switch (Addresses.lastDigitOf(message.address())) {
            case 2 -> SendResult.of(SendResult.Outcome.INVALID_ADDRESS, reference);
            case 3 -> SendResult.of(SendResult.Outcome.FAILED, reference);
            default -> SendResult.of(SendResult.Outcome.ACCEPTED, reference);
        };
    }
}
