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
    private final ReminderDispatchService dispatch;

    public ReminderRunner(ReminderDetectionService detection, ReminderDispatchService dispatch) {
        this.detection = detection;
        this.dispatch = dispatch;
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

        int sent = dispatchDue();
        if (sent > 0) {
            log.info("Delivered {} reminders", sent);
        }

        warnAboutWorkNothingCanDeliver();
        return total;
    }

    /**
     * Reminders on a channel with no provider are left out of the due queue so
     * they cannot starve the ones that can go out. Said out loud once a sweep,
     * because a queue nothing is working through should not also be silent.
     */
    private void warnAboutWorkNothingCanDeliver() {
        long waiting = dispatch.undeliverableBacklog();
        if (waiting > 0) {
            log.warn("{} reminders are waiting on a channel with no provider yet", waiting);
        }
    }

    /**
     * Delivers everything now due, across tenants. Each reminder is dispatched
     * on its own so one failure does not hold up the rest.
     */
    public int dispatchDue() {
        int sent = 0;
        for (UUID reminderId : dispatch.dueReminderIds()) {
            sent += dispatchOne(reminderId);
        }
        return sent;
    }

    /** Delivers the due reminders of one tenant, used by the on-demand run. */
    public int dispatchDueFor(UUID organizationId) {
        int sent = 0;
        for (UUID reminderId : dispatch.dueReminderIdsFor(organizationId)) {
            sent += dispatchOne(reminderId);
        }
        return sent;
    }

    private int dispatchOne(UUID reminderId) {
        try {
            return dispatch.dispatch(reminderId) == ReminderDispatchService.Outcome.SENT ? 1 : 0;
        } catch (RuntimeException ex) {
            log.error("Failed to deliver reminder {}", reminderId, ex);
            return 0;
        }
    }
}
