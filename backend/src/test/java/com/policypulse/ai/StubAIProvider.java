package com.policypulse.ai;

/**
 * Stands in for the model so a test can produce any reading, at any confidence,
 * and assert what the system did about it. The validation rules are the subject
 * of those tests, not the model.
 */
public class StubAIProvider implements AIProvider {
    private volatile IntentAnalysis next;

    public void willReturn(IntentAnalysis analysis) {
        this.next = analysis;
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public IntentAnalysis analyse(ConversationContext context) {
        if (next == null) {
            throw new IllegalStateException("No stubbed analysis; call willReturn first");
        }
        return next;
    }
}
