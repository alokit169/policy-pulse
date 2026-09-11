package com.policypulse.premiums;

import com.policypulse.common.Domain;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure date arithmetic, so no Spring context and no database. */
class PremiumScheduleTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void yearlyPremiumsFallOnTheAnniversaryAndIncludeTheEndDate() {
        List<LocalDate> dates = PremiumSchedule.dueDates(
                d("2024-03-15"), d("2027-03-15"), Domain.PremiumFrequency.YEARLY);

        assertThat(dates).containsExactly(
                d("2024-03-15"), d("2025-03-15"), d("2026-03-15"), d("2027-03-15"));
    }

    @Test
    void quarterlyAndHalfYearlyStepByThreeAndSixMonths() {
        assertThat(PremiumSchedule.dueDates(d("2024-01-01"), d("2024-12-31"), Domain.PremiumFrequency.QUARTERLY))
                .containsExactly(d("2024-01-01"), d("2024-04-01"), d("2024-07-01"), d("2024-10-01"));

        assertThat(PremiumSchedule.dueDates(d("2024-01-01"), d("2024-12-31"), Domain.PremiumFrequency.HALF_YEARLY))
                .containsExactly(d("2024-01-01"), d("2024-07-01"));
    }

    /**
     * The reason each date is computed from the start rather than the previous
     * one. Stepping forward month by month would clamp January's 31st to
     * February's 28th and then keep every later date on the 28th. Anchoring
     * returns to the 31st as soon as the month is long enough.
     */
    @Test
    void monthEndDatesDoNotDriftBackwards() {
        List<LocalDate> dates = PremiumSchedule.dueDates(
                d("2024-01-31"), d("2024-06-30"), Domain.PremiumFrequency.MONTHLY);

        assertThat(dates).containsExactly(
                d("2024-01-31"),
                d("2024-02-29"), // 2024 is a leap year
                d("2024-03-31"), // back to the 31st, not stuck on the 29th
                d("2024-04-30"),
                d("2024-05-31"),
                d("2024-06-30"));
    }

    @Test
    void februaryClampsToThe28thInANonLeapYear() {
        assertThat(PremiumSchedule.dueDates(d("2025-01-31"), d("2025-03-31"), Domain.PremiumFrequency.MONTHLY))
                .containsExactly(d("2025-01-31"), d("2025-02-28"), d("2025-03-31"));
    }

    @Test
    void aTermTooShortForASecondInstalmentStillBillsTheFirst() {
        assertThat(PremiumSchedule.dueDates(d("2024-01-01"), d("2024-06-30"), Domain.PremiumFrequency.YEARLY))
                .containsExactly(d("2024-01-01"));
    }

    @Test
    void incompleteOrBackwardsTermsProduceNothing() {
        assertThat(PremiumSchedule.dueDates(null, d("2024-12-31"), Domain.PremiumFrequency.YEARLY)).isEmpty();
        assertThat(PremiumSchedule.dueDates(d("2024-01-01"), null, Domain.PremiumFrequency.YEARLY)).isEmpty();
        assertThat(PremiumSchedule.dueDates(d("2024-01-01"), d("2024-12-31"), null)).isEmpty();
        assertThat(PremiumSchedule.dueDates(d("2024-12-31"), d("2024-01-01"), Domain.PremiumFrequency.YEARLY)).isEmpty();
    }

    /** A typo in an end date must not fill the table. */
    @Test
    void generationIsCapped() {
        List<LocalDate> dates = PremiumSchedule.dueDates(
                d("2024-01-01"), d("2999-01-01"), Domain.PremiumFrequency.MONTHLY);

        assertThat(dates).hasSize(PremiumSchedule.MAX_INSTALMENTS);
    }

    @Test
    void instalmentStatusFollowsTheDate() {
        LocalDate today = d("2026-09-11");

        assertThat(PremiumSchedule.statusOn(d("2026-09-10"), today)).isEqualTo(Domain.PremiumStatus.OVERDUE);
        assertThat(PremiumSchedule.statusOn(today, today)).isEqualTo(Domain.PremiumStatus.DUE);
        assertThat(PremiumSchedule.statusOn(d("2026-09-12"), today)).isEqualTo(Domain.PremiumStatus.UPCOMING);
    }
}
