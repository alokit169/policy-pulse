package com.policypulse.ai;

import com.policypulse.common.Domain;

import java.time.LocalDate;
import java.util.List;

/**
 * What the assistant made of a call and what the system allowed it to cause.
 *
 * <p>Reported together on purpose: an agent reading this should be able to see
 * that a payment was claimed and that nothing was marked as received because of
 * it.
 */
public record AnalysisResponse(
        String provider,
        Domain.AiIntent intent,
        double confidence,
        LocalDate committedDate,
        String summary,
        String sentiment,
        /** False when the reading was too weak, or the intent is not one to act on. */
        boolean acted,
        String decision,
        List<String> actions) {
}
