package com.policypulse.messaging;

import com.policypulse.common.Domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for a real provider so a test can decide how each send goes.
 *
 * <p>Outcomes are queued, so a test can say "fails, fails, then accepted" and
 * check what the retry rules did in between. Counted across threads, so a test
 * about two sweeps racing can trust the number.
 */
public class StubMessageProvider implements MessageProvider {
    private final Domain.Channel channel;
    private final Deque<SendResult> queued = new ArrayDeque<>();
    private final AtomicInteger sent = new AtomicInteger();
    private volatile OutboundMessage last;

    public StubMessageProvider(Domain.Channel channel) {
        this.channel = channel;
        queued.add(SendResult.of(SendResult.Outcome.ACCEPTED, "stub-ref"));
    }

    /** Back to a clean provider that accepts everything. */
    public synchronized void reset() {
        willReturn(SendResult.Outcome.ACCEPTED);
    }

    public synchronized void willReturn(SendResult.Outcome... outcomes) {
        queued.clear();
        sent.set(0);
        last = null;
        for (SendResult.Outcome outcome : List.of(outcomes)) {
            queued.add(SendResult.of(outcome, "stub-ref"));
        }
    }

    public int sent() {
        return sent.get();
    }

    /** The last message handed over, so a test can read what was said. */
    public OutboundMessage last() {
        return last;
    }

    @Override
    public Domain.Channel channel() {
        return channel;
    }

    @Override
    public String name() {
        return "stub-" + channel.name().toLowerCase();
    }

    @Override
    public synchronized SendResult send(OutboundMessage message) {
        sent.incrementAndGet();
        last = message;
        // Repeats the last queued outcome rather than running out, so a test that
        // only cares about the attempt limit need not queue one per attempt.
        return queued.size() > 1 ? queued.poll() : queued.peek();
    }
}
