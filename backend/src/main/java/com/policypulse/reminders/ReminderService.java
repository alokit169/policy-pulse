package com.policypulse.reminders;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import com.policypulse.organizations.Organization;
import com.policypulse.organizations.OrganizationRepository;
import com.policypulse.reminders.ReminderDetectionService.DetectionResult;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class ReminderService {
    private static final String ENTITY = "ReminderConfiguration";

    private final ReminderRepository reminders;
    private final ReminderConfigurationRepository configurations;
    private final OrganizationRepository organizations;
    private final ReminderDetectionService detection;
    private final AuditService audit;

    public ReminderService(ReminderRepository reminders,
                           ReminderConfigurationRepository configurations,
                           OrganizationRepository organizations,
                           ReminderDetectionService detection,
                           AuditService audit) {
        this.reminders = reminders;
        this.configurations = configurations;
        this.organizations = organizations;
        this.detection = detection;
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
        return ReminderConfigurationResponse.of(configurationFor(orgId), timezoneOf(orgId));
    }

    @Transactional
    public ReminderConfigurationResponse updateConfiguration(ReminderConfigurationRequest request) {
        AuthUser caller = SecurityUtil.current();
        requireManager(caller);

        if (!request.allowedCallingEnd().isAfter(request.allowedCallingStart())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The calling window must end after it starts");
        }

        ReminderConfiguration config = configurationFor(caller.getOrganizationId());
        config.setDaysBeforeDue(request.daysBeforeDue());
        config.setDaysAfterDue(request.daysAfterDue());
        config.setMaxCallAttempts(request.maxCallAttempts());
        config.setRetryDelayMinutes(request.retryDelayMinutes());
        config.setAllowedCallingStart(request.allowedCallingStart());
        config.setAllowedCallingEnd(request.allowedCallingEnd());
        config.setPreferredChannel(request.preferredChannel());
        configurations.save(config);

        audit.record(AuditAction.REMINDER_CONFIG_UPDATED, ENTITY, config.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), null);

        return ReminderConfigurationResponse.of(config, timezoneOf(caller.getOrganizationId()));
    }

    /**
     * Runs detection for the caller's own tenant. The scheduler covers every
     * tenant on its own; this exists so a manager can see the effect of a
     * configuration change without waiting for the next sweep.
     */
    public DetectionResult detectNow() {
        AuthUser caller = SecurityUtil.current();
        requireManager(caller);
        return detection.detectForOrganization(caller.getOrganizationId());
    }

    private void requireManager(AuthUser caller) {
        if (caller.role() == Domain.Role.AGENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only managers can change reminder settings");
        }
    }

    /** Tenants without their own configuration get one on first use. */
    private ReminderConfiguration configurationFor(UUID organizationId) {
        return configurations.findByOrganizationId(organizationId).orElseGet(() -> {
            ReminderConfiguration created = new ReminderConfiguration();
            created.setOrganizationId(organizationId);
            return configurations.save(created);
        });
    }

    private String timezoneOf(UUID organizationId) {
        String configured = organizations.findById(organizationId)
                .map(Organization::getTimezone)
                .orElse(null);
        try {
            return ZoneId.of(configured).getId();
        } catch (DateTimeException | NullPointerException ex) {
            return ZoneId.systemDefault().getId();
        }
    }
}
