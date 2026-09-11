package com.policypulse.messaging;

import com.policypulse.common.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which channels something can actually send on.
 *
 * <p>Built from whatever providers are on the classpath rather than from a list
 * written twice. A channel with no provider is absent here, which is what keeps
 * its reminders out of the delivery queue instead of being picked up and put down
 * every sweep.
 */
@Component
public class MessageProviders {
    private static final Logger log = LoggerFactory.getLogger(MessageProviders.class);

    private final Map<Domain.Channel, MessageProvider> byChannel = new EnumMap<>(Domain.Channel.class);

    public MessageProviders(List<MessageProvider> providers) {
        for (MessageProvider provider : providers) {
            MessageProvider clash = byChannel.put(provider.channel(), provider);
            if (clash != null) {
                // Two providers for one channel means one of them is silently
                // never used, which is worth saying out loud.
                log.warn("Both {} and {} claim {}; using {}",
                        clash.name(), provider.name(), provider.channel(), provider.name());
            }
        }
        log.info("Messaging can send on {}", byChannel.keySet());
    }

    public Optional<MessageProvider> forChannel(Domain.Channel channel) {
        return Optional.ofNullable(byChannel.get(channel));
    }

    /** The channels a provider exists for. */
    public Set<Domain.Channel> supported() {
        return Set.copyOf(byChannel.keySet());
    }
}
