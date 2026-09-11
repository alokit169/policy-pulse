package com.policypulse.reminders;

import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.notifications.NotificationService;
import com.policypulse.voice.CallService;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Delivers reminders whose scheduled moment has arrived.
 *
 * <p>Detection only decides that something needs chasing and when. Keeping
 * delivery separate is what makes the scheduled time mean anything: a reminder
 * raised at four in the morning still waits for the tenant's calling window.
 */
@Service
public class ReminderDispatchService {
    private static final Logger log = LoggerFactory.getLogger(ReminderDispatchService.class);

    public enum Outcome { SENT, CANCELLED, DEFERRED }

    private final ReminderRepository reminders;
    private final PolicyRepository policies;
    private final CustomerRepository customers;
    private final NotificationService notifications;
    private final CallService calls;
    private final ReminderConfigurations configurations;
    private final Clock clock;

    /** One sweep never loads more than this, however much has backed up. */
    private static final org.springframework.data.domain.Pageable BATCH =
            org.springframework.data.domain.PageRequest.of(0, 200);

    /**
     * Channels something can actually deliver today. Email, SMS and WhatsApp
     * arrive with their providers in a later phase; until then their reminders
     * are left alone rather than picked up and put down every sweep, which would
     * crowd out the reminders that can be delivered.
     */
    static final Set<Domain.Channel> DELIVERABLE =
            EnumSet.of(Domain.Channel.IN_APP, Domain.Channel.VOICE);

    public ReminderDispatchService(ReminderRepository reminders, PolicyRepository policies,
                                   CustomerRepository customers, NotificationService notifications,
                                   CallService calls, ReminderConfigurations configurations,
                                   Clock clock) {
        this.reminders = reminders;
        this.policies = policies;
        this.customers = customers;
        this.notifications = notifications;
        this.calls = calls;
        this.configurations = configurations;
        this.clock = clock;
    }

    /**
     * Reminders that are now due to go out. Returns ids rather than entities so
     * each one can be handled in its own transaction by the caller.
     */
    @Transactional(readOnly = true)
    public List<UUID> dueReminderIds() {
        return reminders.findDue(DELIVERABLE, Instant.now(clock), BATCH).stream()
                .map(Reminder::getId)
                .toList();
    }

    /** Due reminders for one tenant only. */
    @Transactional(readOnly = true)
    public List<UUID> dueReminderIdsFor(UUID organizationId) {
        return reminders.findDueForOrganization(organizationId, DELIVERABLE, Instant.now(clock), BATCH).stream()
                .map(Reminder::getId)
                .toList();
    }

    /** How much is queued on a channel nothing can deliver yet. */
    @Transactional(readOnly = true)
    public long undeliverableBacklog() {
        return reminders.countByStatusAndChannelNotIn(Domain.ReminderStatus.PENDING, DELIVERABLE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome dispatch(UUID reminderId) {
        // Claimed in the database rather than checked in memory. Two sweeps can
        // race for the same reminder — the hourly one and a manager running
        // detection by hand — and a read-then-write status check lets both
        // through, which on a voice reminder means ringing the customer twice.
        if (reminders.claimForDelivery(reminderId) == 0) {
            return Outcome.DEFERRED;
        }

        Optional<Reminder> found = reminders.findById(reminderId);
        if (found.isEmpty()) return Outcome.DEFERRED;

        Reminder reminder = found.get();

        // Consent is checked again here, not only at detection. A customer who
        // opts out after a reminder is raised must not still be contacted.
        if (!mayStillContact(reminder)) {
            reminder.setStatus(Domain.ReminderStatus.CANCELLED);
            reminders.save(reminder);
            return Outcome.CANCELLED;
        }

        if (reminder.getChannel() == Domain.Channel.VOICE) {
            return placeCall(reminder);
        }

        if (!DELIVERABLE.contains(reminder.getChannel())) {
            // Email and SMS arrive with their providers in a later phase.
            // Leaving it pending is honest: nothing was sent.
            log.debug("Reminder {} is for {}, which has no provider yet",
                    reminder.getId(), reminder.getChannel());
            return defer(reminder);
        }

        deliverInApp(reminder);

        reminder.setStatus(Domain.ReminderStatus.SENT);
        reminder.setAttemptCount(reminder.getAttemptCount() + 1);
        reminder.setLastAttemptAt(Instant.now(clock));
        reminders.save(reminder);
        return Outcome.SENT;
    }

    /**
     * Hands a voice reminder to the call service, which owns the calling window,
     * the attempt limit and what to do when a number cannot be reached.
     */
    private Outcome placeCall(Reminder reminder) {
        Customer customer = customers.findById(reminder.getCustomerId()).orElse(null);
        if (customer == null) return defer(reminder);

        Policy policy = reminder.getPolicyId() == null ? null
                : policies.findById(reminder.getPolicyId()).orElse(null);

        return switch (calls.placeCall(reminder, customer, policy,
                configurations.forOrganization(reminder.getOrganizationId()))) {
            case ANSWERED -> Outcome.SENT;
            // Put back on purpose: another attempt is already scheduled, or the
            // window has not opened yet and nothing was dialled. CallService has
            // set the moment to try again; this only returns the claim.
            case RETRY_SCHEDULED, OUTSIDE_WINDOW -> defer(reminder);
            case GIVEN_UP -> Outcome.CANCELLED;
        };
    }

    /**
     * Hands a claimed reminder back, so a later sweep can pick it up again.
     * Whatever decided to defer it has already said when that should be.
     */
    private Outcome defer(Reminder reminder) {
        reminder.setStatus(Domain.ReminderStatus.PENDING);
        reminders.save(reminder);
        return Outcome.DEFERRED;
    }

    private boolean mayStillContact(Reminder reminder) {
        Optional<Customer> customer = customers.findById(reminder.getCustomerId());
        if (customer.isEmpty()) return false;

        Customer c = customer.get();
        if (c.isOptedOut() || !c.isCommunicationConsent() || c.getStatus() != Domain.EntityStatus.ACTIVE) {
            return false;
        }

        if (reminder.getPolicyId() == null) return true;

        // A policy that has since lapsed or been surrendered is no longer worth
        // chasing for a premium.
        return policies.findById(reminder.getPolicyId())
                .map(p -> p.getStatus() == Domain.PolicyStatus.ACTIVE)
                .orElse(false);
    }

    private void deliverInApp(Reminder reminder) {
        Optional<Policy> policy = reminder.getPolicyId() == null
                ? Optional.empty()
                : policies.findById(reminder.getPolicyId());
        Optional<Customer> customer = customers.findById(reminder.getCustomerId());

        UUID recipient = policy.map(Policy::getAgentId)
                .orElseGet(() -> customer.map(Customer::getAssignedAgentId).orElse(null));
        if (recipient == null) {
            return;
        }

        String who = customer.map(Customer::fullName).orElse("A customer");
        String title = reminder.getReminderType() == Domain.ReminderType.PREMIUM_OVERDUE
                ? "Premium overdue"
                : "Premium due";
        String body = policy
                .map(p -> "%s, premium %s %s on policy %s.".formatted(
                        who, p.getCurrencyCode(), p.getPremiumAmount(), p.getPolicyNumber()))
                .orElse("%s needs following up.".formatted(who));

        notifications.notifyUser(reminder.getOrganizationId(), recipient, title, body);
    }
}
