package com.policypulse.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
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
     * A count of instalments with their totals.
     *
     * <p>Totals are per currency and never added together. Currency belongs to
     * the policy, so a tenant can hold rupee and dollar policies at once, and one
     * combined figure would be adding dollars to rupees. The count is
     * currency-agnostic and safe to show on its own.
     */
    public record Money(long count, List<CurrencyAmount> amounts) {

        static Money of(List<DashboardRepository.CurrencyTotal> totals) {
            long count = totals.stream().mapToLong(DashboardRepository.CurrencyTotal::getItemCount).sum();

            List<CurrencyAmount> amounts = totals.stream()
                    .map(t -> new CurrencyAmount(
                            t.getCurrencyCode(),
                            t.getTotalAmount() == null ? BigDecimal.ZERO : t.getTotalAmount()))
                    // Stable order, so the page does not reshuffle between loads.
                    .sorted(Comparator.comparing(CurrencyAmount::currencyCode))
                    .toList();

            return new Money(count, amounts);
        }
    }

    public record CurrencyAmount(String currencyCode, BigDecimal amount) {
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
