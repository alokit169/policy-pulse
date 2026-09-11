package com.policypulse.messaging;

/** Small helpers shared by the providers. */
final class Addresses {

    private Addresses() {
    }

    /**
     * Addresses are not written to logs in full. A log line naming who was
     * contacted, and about what, is a leak in a place nobody checks.
     */
    static String mask(String address) {
        if (address == null || address.isBlank()) return "***";

        int at = address.indexOf('@');
        if (at > 0) {
            // Enough to recognise, not enough to write to.
            return address.charAt(0) + "***" + address.substring(at);
        }
        return address.length() < 4 ? "***" : "***" + address.substring(address.length() - 3);
    }

    /** @return the last digit, or -1 when there is none. */
    static int lastDigitOf(String value) {
        if (value == null) return -1;
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) return c - '0';
        }
        return -1;
    }
}
