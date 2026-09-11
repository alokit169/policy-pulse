package com.policypulse.messaging;

import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.policies.Policy;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.reminders.Reminder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What a premium reminder says to the customer.
 *
 * <p>Written for the person receiving it, not for the system sending it. No
 * identifiers beyond the policy number they already have on their documents, no
 * internal wording, and nothing that reads as a threat: a reminder that frightens
 * somebody who is simply late is worse for the agency than one they ignore.
 *
 * <p>Kept short. An SMS is charged by the segment, and a long one costs more
 * without being read more.
 *
 * <p>The figures come from the instalment the reminder is about, not from the
 * policy's summary of what is next. A customer with something still owing from
 * March has a next-premium date of March, so a reminder about September's
 * instalment would otherwise tell them the wrong date about their own money.
 *
 * <p>Every value that came from somebody typing it is reduced to one line before
 * it goes in, so nothing can change the shape of the message it sits in.
 */
public final class ReminderMessages {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private ReminderMessages() {
    }

    public static String subject(Reminder reminder, Policy policy) {
        String number = policy == null ? null : Sanitised.oneLine(policy.getPolicyNumber());
        return reminder.getReminderType() == Domain.ReminderType.PREMIUM_OVERDUE
                ? "Your premium is overdue" + suffix(number)
                : "Your premium is due" + suffix(number);
    }

    /**
     * @param instalment what the reminder is about. Null for reminders raised
     *                   before they carried one, which fall back to the policy's
     *                   own figures rather than saying nothing.
     */
    public static String body(Reminder reminder, Customer customer, Policy policy,
                              PremiumPayment instalment) {
        String name = blank(customer.getFirstName())
                ? "there"
                : Sanitised.oneLine(customer.getFirstName());

        BigDecimal due = amountOf(instalment, policy);
        if (policy == null || due == null) {
            return "Hello %s, please get in touch with us about your policy.".formatted(name);
        }

        String number = Sanitised.oneLine(policy.getPolicyNumber());
        String amount = "INR " + due.toPlainString();
        LocalDate on = dateOf(instalment, policy);
        String when = on == null ? null : DAY.format(on);

        if (reminder.getReminderType() == Domain.ReminderType.PREMIUM_OVERDUE) {
            return when == null
                    ? "Hello %s, a premium of %s on policy %s is overdue. Please get in touch."
                            .formatted(name, amount, number)
                    : "Hello %s, the premium of %s on policy %s was due on %s. Please get in touch."
                            .formatted(name, amount, number, when);
        }

        return when == null
                ? "Hello %s, a premium of %s is due on policy %s."
                        .formatted(name, amount, number)
                : "Hello %s, the premium of %s on policy %s is due on %s."
                        .formatted(name, amount, number, when);
    }

    private static BigDecimal amountOf(PremiumPayment instalment, Policy policy) {
        if (instalment != null && instalment.getAmount() != null) return instalment.getAmount();
        return policy == null ? null : policy.getPremiumAmount();
    }

    private static LocalDate dateOf(PremiumPayment instalment, Policy policy) {
        if (instalment != null && instalment.getDueDate() != null) return instalment.getDueDate();
        return policy == null ? null : policy.getNextPremiumDueDate();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String suffix(String policyNumber) {
        return blank(policyNumber) ? "" : " (" + policyNumber + ")";
    }
}
