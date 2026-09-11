package com.policypulse.reminders;

import com.policypulse.reminders.ReminderDetectionService.DetectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Drives detection across tenants.
 *
 * <p>Separate from ReminderDetectionService on purpose: calling the per-tenant
 * method from inside that same bean would bypass the Spring proxy, and its
 * REQUIRES_NEW transaction would silently not apply. Looping from another bean
 * keeps each tenant genuinely isolated.
 */
@Component
public class ReminderRunner {
    private static final Logger log = LoggerFactory.getLogger(ReminderRunner.class);

    private final ReminderDetectionService detection;

    public ReminderRunner(ReminderDetectionService detection) {
        this.detection = detection;
    }

    public DetectionResult runAll() {
        DetectionResult total = new DetectionResult(0, 0);

        for (UUID organizationId : detection.activeOrganizationIds()) {
            try {
                total = total.plus(detection.detectForOrganization(organizationId));
            } catch (RuntimeException ex) {
                // One tenant's bad data must not stop the rest of the run.
                log.error("Reminder detection failed for organization {}", organizationId, ex);
            }
        }

        if (total.created() > 0) {
            log.info("Reminder detection created {} reminders ({} already present)",
                    total.created(), total.skipped());
        }
        return total;
    }
}
