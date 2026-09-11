package com.policypulse.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What one person sees when they open the application.
 *
 * <p>The figures are already narrowed to what the caller may see: an agent's own
 * book, or the whole organization for anyone senior. {@code scope} says which, so
 * the page can label the numbers honestly rather than implying they cover the
 * whole agency.
 */
public record DashboardResponse(
        Scope scope,
        /** The tenant's own date, which decides what counts as overdue. */
        LocalDate asOf,
        String timezone,
        long activeCustomers,
        long activePolicies,
        long pendingReminders,
        Money overdue,
        Money dueNextSevenDays,
        Money collectedThisMonth,
        List<ActionItem> actionRequired) {

    public enum Scope {
        /** Only the caller's own customers and policies. */
        OWN_BOOK,
        /** Everything in the caller's organization. */
        ORGANIZATION
    }

    /**
     * A count of instalments and what they come to.
     *
     * <p>Safe to add up because every policy is in rupees, which the database
     * enforces. Were more than one currency ever allowed, this would have to
     * become a total per currency: adding dollars to rupees produces a number
     * that means nothing.
     */
    public record Money(long count, BigDecimal amount) {

        static Money of(DashboardRepository.MoneySummary summary) {
            if (summary == null) return new Money(0, BigDecimal.ZERO);
            return new Money(
                    summary.getItemCount(),
                    summary.getTotalAmount() == null ? BigDecimal.ZERO : summary.getTotalAmount());
        }
    }

    /** An overdue premium, with enough detail to act on without another request. */
    public record ActionItem(
            UUID premiumId,
            UUID policyId,
            UUID customerId,
            String customerName,
            String policyNumber,
            String currencyCode,
            BigDecimal amount,
            LocalDate dueDate,
            long daysOverdue) {
    }
}
