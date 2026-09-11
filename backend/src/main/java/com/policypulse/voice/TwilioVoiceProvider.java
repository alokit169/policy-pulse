package com.policypulse.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Placeholder for a real telephony provider, selected with
 * {@code app.voice.provider=twilio}.
 *
 * <p>Not implemented. It exists so the shape of the work is visible and so
 * choosing it fails loudly at startup rather than silently placing no calls,
 * which is the failure that would be noticed last: reminders would keep being
 * marked as attempted and nobody would ever be rung.
 *
 * <p>A real implementation has to deal with things the mock does not:
 *
 * <ul>
 *   <li>Calls are asynchronous. The provider answers immediately and the outcome
 *       arrives later on a webhook, so {@link CallResult} would become a handle
 *       and the reminder would stay in flight until the callback lands.</li>
 *   <li>That webhook is a public endpoint taking instructions about money-related
 *       work, so it must verify the provider's signature and be replay-safe.</li>
 *   <li>Transcription is a separate service again, and arrives later still.</li>
 *   <li>Calls cost money, so a failure that retries forever is expensive rather
 *       than merely wrong.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.voice.provider", havingValue = "twilio")
public class TwilioVoiceProvider implements VoiceProvider {
    private static final Logger log = LoggerFactory.getLogger(TwilioVoiceProvider.class);

    public TwilioVoiceProvider(@Value("${app.voice.api-key:}") String apiKey) {
        log.error("app.voice.provider=twilio is selected but this provider is not implemented");
        if (apiKey.isBlank()) {
            log.error("app.voice.api-key is also unset");
        }
    }

    @Override
    public String name() {
        return "twilio";
    }

    @Override
    public CallResult call(CallRequest request) {
        throw new UnsupportedOperationException(
                "The Twilio provider is not implemented. Set app.voice.provider=mock.");
    }
}
