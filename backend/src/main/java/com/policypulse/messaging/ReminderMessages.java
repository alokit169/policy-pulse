package com.policypulse.messaging;

import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.policies.Policy;
import com.policypulse.reminders.Reminder;

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
 */
public final class ReminderMessages {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private ReminderMessages() {
    }

    public static String subject(Reminder reminder, Policy policy) {
        String number = policy == null ? null : policy.getPolicyNumber();
        return reminder.getReminderType() == Domain.ReminderType.PREMIUM_OVERDUE
                ? "Your premium is overdue" + suffix(number)
                : "Your premium is due" + suffix(number);
    }

    public static String body(Reminder reminder, Customer customer, Policy policy) {
        String name = customer.getFirstName() == null || customer.getFirstName().isBlank()
                ? "there"
                : customer.getFirstName();

        if (policy == null || policy.getPremiumAmount() == null) {
            return "Hello %s, please get in touch with us about your policy.".formatted(name);
        }

        String amount = "INR " + policy.getPremiumAmount().toPlainString();
        String when = policy.getNextPremiumDueDate() == null
                ? null
                : DAY.format(policy.getNextPremiumDueDate());

        if (reminder.getReminderType() == Domain.ReminderType.PREMIUM_OVERDUE) {
            return when == null
                    ? "Hello %s, a premium of %s on policy %s is overdue. Please get in touch."
                            .formatted(name, amount, policy.getPolicyNumber())
                    : "Hello %s, the premium of %s on policy %s was due on %s. Please get in touch."
                            .formatted(name, amount, policy.getPolicyNumber(), when);
        }

        return when == null
                ? "Hello %s, a premium of %s is due on policy %s."
                        .formatted(name, amount, policy.getPolicyNumber())
                : "Hello %s, the premium of %s on policy %s is due on %s."
                        .formatted(name, amount, policy.getPolicyNumber(), when);
    }

    private static String suffix(String policyNumber) {
        return policyNumber == null ? "" : " (" + policyNumber + ")";
    }
}
