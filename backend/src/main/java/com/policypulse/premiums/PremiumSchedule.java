package com.policypulse.premiums;

import com.policypulse.common.Domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out when a policy's premiums fall due. Pure date arithmetic, kept out of
 * the service so it can be tested without a database.
 */
public final class PremiumSchedule {

    /**
     * A monthly policy running a century would otherwise generate 1200 rows. The
     * cap keeps a typo in an end date from filling the table.
     */
    static final int MAX_INSTALMENTS = 600;

    private PremiumSchedule() {
    }

    static int monthsBetween(Domain.PremiumFrequency frequency) {
        return switch (frequency) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case HALF_YEARLY -> 6;
            case YEARLY -> 12;
        };
    }

    /**
     * Due dates from {@code start} up to and including {@code end}.
     *
     * <p>Each date is computed from the start date rather than from the previous
     * one. Adding a month to the 31st clamps to the end of a shorter month, so
     * stepping forward repeatedly would drag every later date back with it: the
     * 31st of January would become the 28th of February and then stay on the
     * 28th for good. Anchoring on the start date keeps it on the 31st wherever
     * the month allows.
     */
    public static List<LocalDate> dueDates(LocalDate start, LocalDate end, Domain.PremiumFrequency frequency) {
        if (start == null || end == null || frequency == null || end.isBefore(start)) {
            return List.of();
        }

        int step = monthsBetween(frequency);
        List<LocalDate> dates = new ArrayList<>();

        for (int i = 0; i < MAX_INSTALMENTS; i++) {
            LocalDate due = start.plusMonths((long) i * step);
            if (due.isAfter(end)) break;
            dates.add(due);
        }
        return List.copyOf(dates);
    }

    /**
     * How an instalment stands on a given day. A back-dated policy is created
     * with instalments that are already due or overdue, rather than pretending
     * they are upcoming.
     */
    public static Domain.PremiumStatus statusOn(LocalDate dueDate, LocalDate today) {
        if (dueDate.isBefore(today)) return Domain.PremiumStatus.OVERDUE;
        if (dueDate.isEqual(today)) return Domain.PremiumStatus.DUE;
        return Domain.PremiumStatus.UPCOMING;
    }
}
