package com.policypulse.messaging;

import com.policypulse.common.Domain;

/**
 * Sends one message on one channel and reports what happened.
 *
 * <p>Whether a message may be sent at all is decided before this is reached:
 * consent, the hour of the tenant's day, how many times the customer has already
 * been tried and whether the address is usable are not a provider's concern, and
 * a provider must never be the thing that enforces them.
 */
public interface MessageProvider {

    /** The one channel this carries. */
    Domain.Channel channel();

    /** A name for logs and for the record, so a message can be traced. */
    String name();

    SendResult send(OutboundMessage message);
}
