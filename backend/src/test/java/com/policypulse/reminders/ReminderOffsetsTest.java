package com.policypulse.reminders;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure parsing, so no Spring context and no database. */
class ReminderOffsetsTest {

    @Test
    void readsTheConfiguredDayCounts() {
        assertThat(ReminderOffsets.parse("10,5,1,0")).containsExactly(10, 5, 1, 0);
    }

    @Test
    void toleratesSpacingAroundTheValues() {
        assertThat(ReminderOffsets.parse(" 10 , 5 ,1 ")).containsExactly(10, 5, 1);
    }

    @Test
    void aSingleValueNeedsNoComma() {
        assertThat(ReminderOffsets.parse("2")).containsExactly(2);
    }

    /**
     * Configuration is edited by people. One unusable entry should cost that
     * entry, not the tenant's whole reminder schedule.
     */
    @Test
    void unusableEntriesAreDroppedRatherThanFailing() {
        assertThat(ReminderOffsets.parse("10,abc,5")).containsExactly(10, 5);
        assertThat(ReminderOffsets.parse("10,,5")).containsExactly(10, 5);
        assertThat(ReminderOffsets.parse("10,-3,5")).containsExactly(10, 5);
    }

    /** A repeated value must not produce the same reminder twice in one run. */
    @Test
    void duplicatesAreCollapsedAndOrderIsKept() {
        assertThat(ReminderOffsets.parse("5,10,5")).containsExactly(5, 10);
    }

    @Test
    void absurdOffsetsAreIgnored() {
        assertThat(ReminderOffsets.parse("10," + (ReminderOffsets.MAX_OFFSET_DAYS + 1)))
                .containsExactly(10);
        assertThat(ReminderOffsets.parse(String.valueOf(ReminderOffsets.MAX_OFFSET_DAYS)))
                .containsExactly(ReminderOffsets.MAX_OFFSET_DAYS);
    }

    @Test
    void nothingConfiguredMeansNoReminders() {
        assertThat(ReminderOffsets.parse(null)).isEmpty();
        assertThat(ReminderOffsets.parse("")).isEmpty();
        assertThat(ReminderOffsets.parse("   ")).isEmpty();
        assertThat(ReminderOffsets.parse("abc")).isEmpty();
    }
}
