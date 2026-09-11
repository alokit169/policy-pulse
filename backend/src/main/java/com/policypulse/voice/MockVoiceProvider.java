package com.policypulse.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Places no calls. Produces a plausible transcript so the rest of the system can
 * be exercised end to end without dialling anyone.
 *
 * <p>Deterministic on the number dialled rather than random: a demo shows a mix
 * of outcomes, and a test that picks a number gets the same outcome every time.
 * A random provider would make every retry test flaky.
 */
@Component
@ConditionalOnProperty(name = "app.voice.provider", havingValue = "mock", matchIfMissing = true)
public class MockVoiceProvider implements VoiceProvider {
    private static final Logger log = LoggerFactory.getLogger(MockVoiceProvider.class);

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public CallResult call(CallRequest request) {
        log.info("Mock call to {} (attempt {})", masked(request.phoneNumber()), request.attempt());

        String reference = "mock-" + UUID.randomUUID();
        return switch (lastDigitOf(request.phoneNumber())) {
            case 0 -> CallResult.of(CallResult.Outcome.NO_ANSWER, reference);
            case 1 -> CallResult.of(CallResult.Outcome.BUSY, reference);
            case 2 -> CallResult.of(CallResult.Outcome.INVALID_NUMBER, reference);
            default -> answered(request, reference);
        };
    }

    private CallResult answered(CallRequest request, String reference) {
        List<CallResult.Line> transcript = new ArrayList<>();

        String opening = request.premiumDueOn() == null
                ? "Hello %s, calling from your insurance agency.".formatted(nameOrThere(request))
                : "Hello %s, your premium of %s is due on %s.".formatted(
                nameOrThere(request), request.amountDue(), request.premiumDueOn());

        transcript.add(new CallResult.Line("ASSISTANT", opening));
        // A stand-in customer who commits, which is the path worth being able to
        // demonstrate. Everything after this still goes through validation.
        transcript.add(new CallResult.Line("CUSTOMER", "Yes, I will pay on Friday."));
        transcript.add(new CallResult.Line("ASSISTANT", "Thank you, I have noted that."));

        return new CallResult(CallResult.Outcome.ANSWERED, 42, reference, transcript);
    }

    private String nameOrThere(CallRequest request) {
        return request.customerName() == null || request.customerName().isBlank()
                ? "there"
                : request.customerName();
    }

    private int lastDigitOf(String phoneNumber) {
        if (phoneNumber == null) return -1;
        for (int i = phoneNumber.length() - 1; i >= 0; i--) {
            char c = phoneNumber.charAt(i);
            if (Character.isDigit(c)) return c - '0';
        }
        return -1;
    }

    /** Numbers are not written to logs in full. */
    private String masked(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) return "***";
        return "***" + phoneNumber.substring(phoneNumber.length() - 3);
    }
}
