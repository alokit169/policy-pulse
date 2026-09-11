package com.policypulse.ai;

import com.policypulse.common.Domain;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule-based and deterministic, so it needs no Spring and no database. */
class MockAIProviderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15); // a Tuesday

    private final MockAIProvider provider = new MockAIProvider();

    private IntentAnalysis read(String... lines) {
        List<ConversationContext.Line> transcript = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            transcript.add(new ConversationContext.Line(lines[i], lines[i + 1]));
        }
        return provider.analyse(new ConversationContext(TODAY, "Asha", TODAY.plusDays(2), transcript));
    }

    @Test
    void aPromiseToPayIsRecognisedWithTheDayNamed() {
        IntentAnalysis analysis = read("AGENT", "Your premium is due.", "CUSTOMER", "I will pay on Friday.");

        assertThat(analysis.intent()).isEqualTo(Domain.AiIntent.PAYMENT_COMMITMENT);
        assertThat(analysis.committedDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(analysis.confidence()).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void tomorrowIsResolvedAgainstTheTenantsToday() {
        assertThat(read("CUSTOMER", "I will pay tomorrow.").committedDate()).isEqualTo(TODAY.plusDays(1));
    }

    /**
     * The most important rule here. Only the customer can promise anything, so
     * what the agent said is context and never a commitment. Reading it would let
     * the system talk itself into a promise nobody made.
     */
    @Test
    void onlyWhatTheCustomerSaidIsRead() {
        IntentAnalysis analysis = read(
                "AGENT", "So you will pay on Friday, yes?",
                "CUSTOMER", "Hmm.");

        assertThat(analysis.intent())
                .as("the agent putting words in their mouth is not a commitment")
                .isNotEqualTo(Domain.AiIntent.PAYMENT_COMMITMENT);
        assertThat(analysis.committedDate()).isNull();
    }

    @Test
    void anAssistantRepeatingItselfIsNotACommitmentEither() {
        IntentAnalysis analysis = read(
                "ASSISTANT", "I will pay on Friday on your behalf.",
                "CUSTOMER", "What?");

        assertThat(analysis.intent()).isNotEqualTo(Domain.AiIntent.PAYMENT_COMMITMENT);
    }

    @Test
    void aPromiseWithNoDayIsReportedLessConfidently() {
        IntentAnalysis vague = read("CUSTOMER", "I will pay, do not worry.");
        IntentAnalysis dated = read("CUSTOMER", "I will pay on Thursday.");

        assertThat(vague.intent()).isEqualTo(Domain.AiIntent.PAYMENT_COMMITMENT);
        assertThat(vague.committedDate()).isNull();
        assertThat(vague.confidence())
                .as("a promise with no day is a weaker reading and should say so")
                .isLessThan(dated.confidence());
    }

    @Test
    void aClaimOfHavingPaidIsRecognised() {
        assertThat(read("CUSTOMER", "I have paid already.").intent())
                .isEqualTo(Domain.AiIntent.PAYMENT_CONFIRMED);
    }

    @Test
    void anOptOutOutranksEverythingElseInTheSameCall() {
        IntentAnalysis analysis = read("CUSTOMER", "I will pay on Friday but do not call me again.");

        assertThat(analysis.intent())
                .as("a request to stop contact must not be buried under a promise")
                .isEqualTo(Domain.AiIntent.OPT_OUT);
        assertThat(analysis.confidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void aSilentCustomerYieldsNothingToActOn() {
        IntentAnalysis analysis = read("AGENT", "Hello? Are you there?");

        assertThat(analysis.intent()).isEqualTo(Domain.AiIntent.UNKNOWN);
        assertThat(analysis.confidence()).isZero();
    }

    @Test
    void unrecognisedSpeechIsReportedAsWeak() {
        IntentAnalysis analysis = read("CUSTOMER", "The weather has been terrible.");

        assertThat(analysis.intent()).isEqualTo(Domain.AiIntent.GENERAL_QUERY);
        assertThat(analysis.confidence())
                .as("below the acting threshold, so nothing is done on it")
                .isLessThan(ActionValidationService.ACTING_THRESHOLD);
    }

    @Test
    void inabilityToPayIsNotMistakenForAPromise() {
        assertThat(read("CUSTOMER", "I cannot pay this month.").intent())
                .isEqualTo(Domain.AiIntent.CANNOT_PAY);
    }

    @Test
    void askingForAPersonIsRecognised() {
        assertThat(read("CUSTOMER", "Let me speak to a person please.").intent())
                .isEqualTo(Domain.AiIntent.REQUEST_HUMAN_AGENT);
    }
}
