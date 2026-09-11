package com.policypulse.voice;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Stands in for telephony so a test can decide how each call goes.
 *
 * <p>Outcomes are queued, so a test can say "rings out, rings out, then
 * answered" and check what the retry rules did in between.
 */
public class StubVoiceProvider implements VoiceProvider {
    private final Deque<CallResult> queued = new ArrayDeque<>();
    private int callsPlaced;

    public void willReturn(CallResult... results) {
        queued.clear();
        callsPlaced = 0;
        queued.addAll(List.of(results));
    }

    public int callsPlaced() {
        return callsPlaced;
    }

    public static CallResult answered() {
        return new CallResult(CallResult.Outcome.ANSWERED, 30, "stub-ref", List.of(
                new CallResult.Line("ASSISTANT", "Your premium is due."),
                new CallResult.Line("CUSTOMER", "I will pay on Friday.")));
    }

    public static CallResult outcome(CallResult.Outcome outcome) {
        return CallResult.of(outcome, "stub-ref");
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public CallResult call(CallRequest request) {
        callsPlaced++;
        // Repeats the last queued outcome rather than running out, so a test that
        // only cares about the attempt limit need not queue one per attempt.
        return queued.size() > 1 ? queued.poll() : queued.peek();
    }
}
