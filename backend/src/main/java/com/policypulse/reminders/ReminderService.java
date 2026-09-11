package com.policypulse.reminders;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.reminders.ReminderDetectionService.DetectionResult;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ReminderService {
    private static final String ENTITY = "ReminderConfiguration";

    private final ReminderRepository reminders;
    private final ReminderConfigurationRepository configurations;
    private final ReminderConfigurations configurationResolver;
    private final OrganizationZones zones;
    private final ReminderDetectionService detection;
    private final ReminderRunner runner;
    private final AuditService audit;

    public ReminderService(ReminderRepository reminders,
                           ReminderConfigurationRepository configurations,
                           ReminderConfigurations configurationResolver,
                           OrganizationZones zones,
                           ReminderDetectionService detection,
                           ReminderRunner runner,
                           AuditService audit) {
        this.reminders = reminders;
        this.configurations = configurations;
        this.configurationResolver = configurationResolver;
        this.zones = zones;
        this.detection = detection;
        this.runner = runner;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReminderResponse> search(Domain.ReminderStatus status, Pageable pageable) {
        UUID orgId = SecurityUtil.current().getOrganizationId();

        Page<Reminder> page = status == null
                ? reminders.findByOrganizationId(orgId, pageable)
                : reminders.findByOrganizationIdAndStatus(orgId, status, pageable);

        return new PageResponse<>(
                page.getContent().stream().map(ReminderResponse::of).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize());
    }

    @Transactional
    public ReminderConfigurationResponse getConfiguration() {
        UUID orgId = SecurityUtil.current().getOrganizationId();
        return ReminderConfigurationResponse.of(configurationResolver.forOrganization(orgId), timezoneOf(orgId));
    }

    @Transactional
    public ReminderConfigurationResponse updateConfiguration(ReminderConfigurationRequest request) {
        AuthUser caller = SecurityUtil.current();
        requireManager(caller);

        if (!request.allowedCallingEnd().isAfter(request.allowedCallingStart())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The calling window must end after it starts");
        }

        ReminderConfiguration config = configurationResolver.forOrganization(caller.getOrganizationId());
        config.setDaysBeforeDue(request.daysBeforeDue());
        config.setDaysAfterDue(request.daysAfterDue());
        config.setMaxCallAttempts(request.maxCallAttempts());
        config.setRetryDelayMinutes(request.retryDelayMinutes());
        config.setAllowedCallingStart(request.allowedCallingStart());
        config.setAllowedCallingEnd(request.allowedCallingEnd());
        config.setPreferredChannel(request.preferredChannel());
        configurations.save(config);

        // The window has moved, so anything told to wait for the old one has to
        // be reconsidered. Otherwise a manager who widens the window to catch up
        // on today's calls sees nothing happen until tomorrow.
        reminders.wakeRemindersWaitingOnTheCallingWindow(caller.getOrganizationId());

        audit.record(AuditAction.REMINDER_CONFIG_UPDATED, ENTITY, config.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), null);

        return ReminderConfigurationResponse.of(config, timezoneOf(caller.getOrganizationId()));
    }

    /**
     * Runs detection for the caller's own tenant. The scheduler covers every
     * tenant on its own; this exists so a manager can see the effect of a
     * configuration change without waiting for the next sweep.
     */
    public DetectionRun detectNow() {
        AuthUser caller = SecurityUtil.current();
        requireManager(caller);

        DetectionResult detected = detection.detectForOrganization(caller.getOrganizationId());
        int sent = runner.dispatchDueFor(caller.getOrganizationId());
        return new DetectionRun(detected.created(), detected.skipped(), sent);
    }

    /** What one on-demand run did: what it raised, and what it then delivered. */
    public record DetectionRun(int created, int skipped, int sent) {
    }

    private void requireManager(AuthUser caller) {
        if (caller.role() == Domain.Role.AGENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only managers can change reminder settings");
        }
    }

    private String timezoneOf(UUID organizationId) {
        return zones.zoneOf(organizationId).getId();
    }
}
