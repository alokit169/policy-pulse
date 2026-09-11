package com.policypulse.reminders;

import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.organizations.OrganizationRepository;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Finds premiums that need chasing and records a reminder for each.
 *
 * <p>Designed to be run repeatedly without consequence. Every reminder carries
 * an idempotency key built from what it is about rather than when it was made,
 * and the column is unique, so a second run the same day, an overlapping run, or
 * a retry after a crash all converge on the same set of reminders.
 */
@Service
public class ReminderDetectionService {
    private static final Logger log = LoggerFactory.getLogger(ReminderDetectionService.class);

    /** Instalments still worth chasing. Paid and waived ones are settled. */
    private static final Set<Domain.PremiumStatus> CHASEABLE = EnumSet.of(
            Domain.PremiumStatus.UPCOMING, Domain.PremiumStatus.DUE, Domain.PremiumStatus.OVERDUE);

    private final OrganizationRepository organizations;
    private final ReminderConfigurations configurationResolver;
    private final OrganizationZones zones;
    private final PremiumPaymentRepository premiums;
    private final PolicyRepository policies;
    private final CustomerRepository customers;
    private final ReminderRepository reminders;
    private final Clock clock;

    public ReminderDetectionService(OrganizationRepository organizations,
                                    ReminderConfigurations configurationResolver,
                                    OrganizationZones zones,
                                    PremiumPaymentRepository premiums,
                                    PolicyRepository policies,
                                    CustomerRepository customers,
                                    ReminderRepository reminders,
                                    Clock clock) {
        this.organizations = organizations;
        this.configurationResolver = configurationResolver;
        this.zones = zones;
        this.premiums = premiums;
        this.policies = policies;
        this.customers = customers;
        this.reminders = reminders;
        this.clock = clock;
    }

    public record DetectionResult(int created, int skipped) {
        DetectionResult plus(DetectionResult other) {
            return new DetectionResult(created + other.created, skipped + other.skipped);
        }
    }

    /**
     * Tenants to run detection for. Iterating and calling back into this bean
     * would go through {@code this} rather than the Spring proxy, so the
     * per-tenant transaction below would never be applied; ReminderRunner does
     * the looping from outside instead.
     */
    public List<UUID> activeOrganizationIds() {
        return organizations.findAll().stream()
                .filter(o -> o.getStatus() == Domain.EntityStatus.ACTIVE)
                .map(Organization::getId)
                .toList();
    }

    /**
     * One tenant, in its own transaction, so a failure cannot roll back another
     * tenant's reminders.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DetectionResult detectForOrganization(UUID organizationId) {
        Organization organization = organizations.findById(organizationId).orElseThrow();
        ReminderConfiguration config = configurationResolver.forOrganization(organizationId);
        ZoneId zone = zones.zoneOf(organization);

        // "Today" is the tenant's today. Evaluating an Indian agency's due dates
        // in UTC would shift every reminder by a day for part of each day.
        LocalDate today = LocalDate.now(clock.withZone(zone));

        DetectionResult result = new DetectionResult(0, 0);

        for (int daysBefore : ReminderOffsets.parse(config.getDaysBeforeDue())) {
            result = result.plus(detect(organizationId, today.plusDays(daysBefore),
                    Domain.ReminderType.PREMIUM_DUE, daysBefore, today, zone, config));
        }

        for (int daysAfter : ReminderOffsets.parse(config.getDaysAfterDue())) {
            if (daysAfter == 0) continue; // Already covered by the on-the-day reminder.
            result = result.plus(detect(organizationId, today.minusDays(daysAfter),
                    Domain.ReminderType.PREMIUM_OVERDUE, daysAfter, today, zone, config));
        }

        return result;
    }

    private DetectionResult detect(UUID organizationId, LocalDate dueDate, Domain.ReminderType type,
                                   int offsetDays, LocalDate today, ZoneId zone, ReminderConfiguration config) {
        List<PremiumPayment> instalments =
                premiums.findByOrganizationIdAndDueDateAndStatusIn(organizationId, dueDate, CHASEABLE);

        int created = 0;
        int skipped = 0;

        for (PremiumPayment instalment : instalments) {
            if (createReminder(instalment, type, offsetDays, today, zone, config)) {
                created++;
            } else {
                skipped++;
            }
        }
        return new DetectionResult(created, skipped);
    }

    /** @return true when a reminder was written, false when it was not needed. */
    private boolean createReminder(PremiumPayment instalment, Domain.ReminderType type, int offsetDays,
                                   LocalDate today, ZoneId zone, ReminderConfiguration config) {
        Optional<Policy> maybePolicy = policies.findById(instalment.getPolicyId());
        if (maybePolicy.isEmpty()) return false;

        Policy policy = maybePolicy.get();
        if (policy.getStatus() != Domain.PolicyStatus.ACTIVE) {
            return false;
        }

        Optional<Customer> maybeCustomer = customers.findById(policy.getCustomerId());
        if (maybeCustomer.isEmpty()) return false;

        Customer customer = maybeCustomer.get();
        // Consent is checked before anything is written, so an opted-out customer
        // leaves no queued work that someone could later act on by mistake.
        if (customer.isOptedOut() || !customer.isCommunicationConsent()
                || customer.getStatus() != Domain.EntityStatus.ACTIVE) {
            return false;
        }

        // Built from what the reminder is about, not when it was made, so a
        // repeated run produces the same key and writes nothing.
        String key = "%s:%s:%s:%d".formatted(type, policy.getId(), instalment.getDueDate(), offsetDays);
        if (reminders.existsByIdempotencyKey(key)) {
            return false;
        }

        Reminder reminder = new Reminder();
        reminder.setOrganizationId(policy.getOrganizationId());
        reminder.setCustomerId(customer.getId());
        reminder.setPolicyId(policy.getId());
        reminder.setReminderType(type);
        reminder.setChannel(config.getPreferredChannel());
        reminder.setStatus(Domain.ReminderStatus.PENDING);
        reminder.setIdempotencyKey(key);
        // Due at the start of the tenant's calling window, so nothing is ever
        // queued for the middle of the night.
        reminder.setScheduledAt(
                ZonedDateTime.of(today, config.getAllowedCallingStart(), zone).toInstant());

        try {
            reminders.save(reminder);
            reminders.flush();
        } catch (DataIntegrityViolationException duplicate) {
            // Another run inserted the same key between the check and the write.
            log.debug("Reminder {} already exists", key);
            return false;
        }

        // Deliberately does not notify anyone here. Delivery belongs to
        // ReminderDispatchService, which waits until the scheduled moment; doing
        // it here would make the calling window decorative.
        return true;
    }

}
