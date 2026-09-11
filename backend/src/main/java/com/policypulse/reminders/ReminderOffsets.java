package com.policypulse.reminders;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the comma separated day offsets a tenant configures, such as
 * "10,5,1,0" for reminders ten, five and one day before a premium falls due and
 * again on the day itself.
 *
 * <p>Pure parsing, kept out of the detector so it can be tested on its own.
 */
public final class ReminderOffsets {

    /**
     * An offset beyond this is almost certainly a typo, and each one costs a
     * query per tenant per run.
     */
    static final int MAX_OFFSET_DAYS = 365;

    private ReminderOffsets() {
    }

    /**
     * Parses configured offsets, ignoring anything that is not a usable day
     * count. Configuration is edited by people: one bad entry should drop that
     * entry, not stop a tenant's reminders altogether.
     *
     * <p>Duplicates are collapsed and order is preserved, so repeating a value
     * cannot produce the same reminder twice in one run.
     */
    public static List<Integer> parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }

        Set<Integer> offsets = new LinkedHashSet<>();
        for (String piece : configured.split(",")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) continue;

            try {
                int days = Integer.parseInt(trimmed);
                if (days >= 0 && days <= MAX_OFFSET_DAYS) {
                    offsets.add(days);
                }
            } catch (NumberFormatException ignored) {
                // Not a number, so not an offset.
            }
        }
        return List.copyOf(new ArrayList<>(offsets));
    }
}
