package com.policypulse.messaging;

/**
 * Keeps what an agency typed from changing the shape of what we send.
 *
 * <p>A policy number is whatever somebody entered, bounded only in length, and it
 * goes into an email subject. A subject is a header, and a header ends at the
 * first line break: one containing {@code \r\n} lets the rest of the value become
 * headers of its own, which is how a message acquires a Bcc nobody asked for.
 *
 * <p>Applied to every value interpolated into a message, so a template cannot be
 * broken out of, and again to the subject before it is handed over, so a template
 * added later is covered without anybody remembering to.
 */
public final class Sanitised {

    private Sanitised() {
    }

    /**
     * One line, with runs of whitespace collapsed and control characters dropped.
     * Null in, null out, so a caller can tell a missing value from an empty one.
     */
    public static String oneLine(String value) {
        if (value == null) return null;

        StringBuilder out = new StringBuilder(value.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace && !out.isEmpty()) out.append(' ');
                lastWasSpace = true;
            } else if (!Character.isISOControl(c)) {
                out.append(c);
                lastWasSpace = false;
            }
        }
        // A value that was only whitespace leaves nothing but a trailing space.
        return out.toString().strip();
    }
}
