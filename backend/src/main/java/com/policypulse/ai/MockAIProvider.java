package com.policypulse.ai;

import com.policypulse.common.Domain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A stand-in for a language model, used until a real one is wired up.
 *
 * <p>Rule-based and therefore deterministic, which is what makes the validation
 * layer testable: a test can produce any intent it likes, at any confidence, and
 * assert what the system did about it.
 *
 * <p>Only lines the customer spoke are read. What the agent or the assistant said
 * is context, never a commitment, and treating "so you will pay on Friday?" as a
 * promise would let the system talk itself into one.
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAIProvider implements AIProvider {

    /** Phrases that, from a customer, read as each intent. Order matters: first match wins. */
    private static final List<Map.Entry<Domain.AiIntent, List<String>>> RULES = List.of(
            Map.entry(Domain.AiIntent.OPT_OUT,
                    List.of("do not call", "don't call", "stop calling", "remove my number", "unsubscribe")),
            Map.entry(Domain.AiIntent.WRONG_NUMBER,
                    List.of("wrong number", "no such person", "you have the wrong")),
            Map.entry(Domain.AiIntent.REQUEST_HUMAN_AGENT,
                    List.of("speak to a person", "speak to someone", "talk to a human", "put me through")),
            Map.entry(Domain.AiIntent.PAYMENT_CONFIRMED,
                    List.of("already paid", "i have paid", "i've paid", "paid yesterday", "payment is done")),
            Map.entry(Domain.AiIntent.CANNOT_PAY,
                    List.of("cannot pay", "can't pay", "no money", "lost my job", "cannot afford")),
            Map.entry(Domain.AiIntent.PAYMENT_COMMITMENT,
                    List.of("i will pay", "i'll pay", "will pay on", "pay it on", "pay by")),
            Map.entry(Domain.AiIntent.PAYMENT_DELAYED,
                    List.of("need more time", "next month", "delay", "postpone")),
            Map.entry(Domain.AiIntent.CALL_LATER,
                    List.of("call me later", "call back", "busy right now", "not a good time")),
            Map.entry(Domain.AiIntent.NO_LONGER_INTERESTED,
                    List.of("not interested", "cancel the policy", "want to surrender")),
            Map.entry(Domain.AiIntent.DOCUMENT_REQUEST,
                    List.of("send me the receipt", "need the document", "send the statement")),
            Map.entry(Domain.AiIntent.MATURITY_QUERY,
                    List.of("when does it mature", "maturity")),
            Map.entry(Domain.AiIntent.BONUS_QUERY,
                    List.of("bonus")),
            Map.entry(Domain.AiIntent.POLICY_QUERY,
                    List.of("my policy", "how much cover", "sum assured")));

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.of(
            "monday", DayOfWeek.MONDAY,
            "tuesday", DayOfWeek.TUESDAY,
            "wednesday", DayOfWeek.WEDNESDAY,
            "thursday", DayOfWeek.THURSDAY,
            "friday", DayOfWeek.FRIDAY,
            "saturday", DayOfWeek.SATURDAY,
            "sunday", DayOfWeek.SUNDAY);

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public IntentAnalysis analyse(ConversationContext context) {
        String customerSaid = context.transcript().stream()
                .filter(line -> "CUSTOMER".equalsIgnoreCase(line.sender()))
                .map(line -> line.text().toLowerCase(Locale.ROOT))
                .reduce("", (all, line) -> all + " " + line)
                .trim();

        if (customerSaid.isBlank()) {
            // The customer said nothing, so there is nothing to read into.
            return IntentAnalysis.unknown("No customer speech in the transcript.");
        }

        for (Map.Entry<Domain.AiIntent, List<String>> rule : RULES) {
            if (rule.getValue().stream().anyMatch(customerSaid::contains)) {
                return build(rule.getKey(), customerSaid, context);
            }
        }
        return new IntentAnalysis(Domain.AiIntent.GENERAL_QUERY, 0.4, null, null,
                "Customer spoke but nothing specific was recognised.", "NEUTRAL");
    }

    private IntentAnalysis build(Domain.AiIntent intent, String customerSaid, ConversationContext context) {
        LocalDate committed = intent == Domain.AiIntent.PAYMENT_COMMITMENT
                ? namedDate(customerSaid, context.today())
                : null;

        // A commitment with no date is a weaker reading than one with a day in it,
        // so it is reported as such rather than being dressed up.
        double confidence = switch (intent) {
            case OPT_OUT, WRONG_NUMBER, REQUEST_HUMAN_AGENT -> 0.95;
            case PAYMENT_CONFIRMED, CANNOT_PAY -> 0.85;
            case PAYMENT_COMMITMENT -> committed == null ? 0.55 : 0.9;
            default -> 0.75;
        };

        return new IntentAnalysis(intent, confidence, committed, null,
                summaryFor(intent), sentimentFor(intent));
    }

    /** "Friday" and "tomorrow" resolved against the tenant's own today. */
    private LocalDate namedDate(String customerSaid, LocalDate today) {
        if (customerSaid.contains("tomorrow")) return today.plusDays(1);
        if (customerSaid.contains("today")) return today;

        for (Map.Entry<String, DayOfWeek> day : WEEKDAYS.entrySet()) {
            if (customerSaid.contains(day.getKey())) {
                // "next" is explicit; a bare weekday means the coming one.
                return today.with(TemporalAdjusters.next(day.getValue()));
            }
        }
        return null;
    }

    private String summaryFor(Domain.AiIntent intent) {
        return switch (intent) {
            case PAYMENT_COMMITMENT -> "Customer said they would pay.";
            case PAYMENT_CONFIRMED -> "Customer said the premium has already been paid.";
            case CANNOT_PAY -> "Customer said they are unable to pay.";
            case OPT_OUT -> "Customer asked not to be contacted again.";
            case WRONG_NUMBER -> "The number does not belong to this customer.";
            case REQUEST_HUMAN_AGENT -> "Customer asked to speak to a person.";
            case CALL_LATER -> "Customer asked to be called back later.";
            default -> "Customer raised a query.";
        };
    }

    private String sentimentFor(Domain.AiIntent intent) {
        return switch (intent) {
            case PAYMENT_COMMITMENT, PAYMENT_CONFIRMED -> "POSITIVE";
            case CANNOT_PAY, OPT_OUT, NO_LONGER_INTERESTED -> "NEGATIVE";
            default -> "NEUTRAL";
        };
    }
}
