package com.policypulse.voice;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.Domain;
import com.policypulse.conversations.Conversation;
import com.policypulse.conversations.ConversationMessage;
import com.policypulse.conversations.ConversationMessageRepository;
import com.policypulse.conversations.ConversationRepository;
import com.policypulse.customers.Customer;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.policies.Policy;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.reminders.Reminder;
import com.policypulse.reminders.ReminderConfiguration;
import com.policypulse.reminders.ReminderRepository;
import com.policypulse.tasks.HumanTask;
import com.policypulse.tasks.HumanTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Places the call behind a voice reminder, and records what came of it.
 *
 * <p>Runs from the scheduler, so there is no signed-in user: nothing here may
 * reach for a security context, and every record it writes carries the tenant
 * taken from the reminder rather than from a caller.
 *
 * <p>The provider is called inside the transaction, which is only tolerable
 * because the mock returns at once. A real telephony provider must not be wired
 * in here as a blocking HTTP call: it would hold a database connection open for
 * the length of a phone call. It answers immediately and reports the outcome on
 * a webhook instead, which is the work described on TwilioVoiceProvider.
 *
 * <p>Three things are checked before a number is dialled, and a provider is
 * trusted with none of them.
 *
 * <ul>
 *   <li><b>The calling window.</b> Checked against the clock at the moment of
 *       dialling, in the tenant's own timezone. A reminder queued for nine in the
 *       morning can be picked up hours late, and ringing someone at eleven at
 *       night is the kind of mistake that ends an agency.</li>
 *   <li><b>How many times they have been tried.</b> Past the tenant's limit the
 *       reminder is given up on and a person is told, rather than the customer
 *       being rung indefinitely.</li>
 *   <li><b>Whether the number is usable at all.</b> A number that cannot be
 *       dialled is not retried, because trying again cannot help.</li>
 * </ul>
 */
@Service
public class CallService {
    private static final Logger log = LoggerFactory.getLogger(CallService.class);

    public enum Decision {
        /** A call was placed and answered. */
        ANSWERED,
        /** A call was placed but not answered; another attempt is scheduled. */
        RETRY_SCHEDULED,
        /** Outside the tenant's calling window. Nothing was dialled and nothing changed. */
        OUTSIDE_WINDOW,
        /** Out of attempts, or the number is unusable. A person has been told. */
        GIVEN_UP
    }

    private final VoiceProvider provider;
    private final PremiumPaymentRepository premiums;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ReminderRepository reminders;
    private final HumanTaskRepository humanTasks;
    private final OrganizationZones zones;
    private final AuditService audit;
    private final Clock clock;

    public CallService(VoiceProvider provider, PremiumPaymentRepository premiums,
                       ConversationRepository conversations,
                       ConversationMessageRepository messages, ReminderRepository reminders,
                       HumanTaskRepository humanTasks, OrganizationZones zones,
                       AuditService audit, Clock clock) {
        this.provider = provider;
        this.premiums = premiums;
        this.conversations = conversations;
        this.messages = messages;
        this.reminders = reminders;
        this.humanTasks = humanTasks;
        this.zones = zones;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Decision placeCall(Reminder reminder, Customer customer, Policy policy,
                              ReminderConfiguration config) {
        ZoneId zone = zones.zoneOf(reminder.getOrganizationId());

        if (!insideCallingWindow(config, zone)) {
            // Told when to come back, rather than looked at again every sweep.
            // A reminder that keeps its old moment stays at the head of the due
            // queue all night, ahead of work that could actually go out.
            reminder.setNextAttemptAt(nextWindowOpening(config, zone));
            reminders.save(reminder);
            log.debug("Reminder {} is outside the calling window for {}, waiting until {}",
                    reminder.getId(), zone, reminder.getNextAttemptAt());
            return Decision.OUTSIDE_WINDOW;
        }

        if (reminder.getAttemptCount() >= config.getMaxCallAttempts()) {
            return giveUp(reminder, customer, "NO_ANSWER_AFTER_" + reminder.getAttemptCount() + "_ATTEMPTS");
        }

        // The column is NOT NULL, so this is about an empty or whitespace value
        // from an import or a correction, not a missing one.
        if (customer.getPhone() == null || customer.getPhone().isBlank()) {
            return giveUp(reminder, customer, "NO_PHONE_NUMBER");
        }

        // The instalment the reminder names, not whatever the policy holds as
        // next: a customer with something still owing from March would otherwise
        // be read the March date on a call about September.
        PremiumPayment instalment = reminder.getPremiumPaymentId() == null ? null
                : premiums.findById(reminder.getPremiumPaymentId()).orElse(null);

        CallResult result = provider.call(new CallRequest(
                customer.getPhone(),
                customer.getFirstName(),
                dueDateFor(instalment, policy),
                amountFor(instalment, policy),
                reminder.getAttemptCount() + 1));

        // Counted whatever happened, so a provider that always fails still runs
        // out of attempts instead of being retried for ever.
        reminder.setAttemptCount(reminder.getAttemptCount() + 1);
        reminder.setLastAttemptAt(Instant.now(clock));

        audit.record(AuditAction.CALL_PLACED, "Reminder", reminder.getId().toString(),
                reminder.getOrganizationId(), null, provider.name(),
                "%s attempt=%d".formatted(result.outcome(), reminder.getAttemptCount()));

        if (result.outcome() == CallResult.Outcome.ANSWERED) {
            return recordAnsweredCall(reminder, customer, policy, result);
        }
        if (result.outcome() == CallResult.Outcome.INVALID_NUMBER) {
            return giveUp(reminder, customer, "INVALID_PHONE_NUMBER");
        }
        // Asked of the outcome rather than assumed by a default branch. A new
        // outcome nobody should be rung again over — a number asking to be left
        // alone, say — would otherwise be retried simply for being new.
        if (!result.outcome().worthRetrying()) {
            return giveUp(reminder, customer, result.outcome().name());
        }
        return scheduleRetry(reminder, customer, config, result);
    }

    private java.time.LocalDate dueDateFor(PremiumPayment instalment, Policy policy) {
        if (instalment != null && instalment.getDueDate() != null) return instalment.getDueDate();
        return policy == null ? null : policy.getNextPremiumDueDate();
    }

    private java.math.BigDecimal amountFor(PremiumPayment instalment, Policy policy) {
        if (instalment != null && instalment.getAmount() != null) return instalment.getAmount();
        return policy == null ? null : policy.getPremiumAmount();
    }

    /**
     * The window is a property of the tenant's day, so it is read in the tenant's
     * zone rather than the server's.
     */
    private boolean insideCallingWindow(ReminderConfiguration config, ZoneId zone) {
        LocalTime now = Instant.now(clock).atZone(zone).toLocalTime();
        return !now.isBefore(config.getAllowedCallingStart()) && now.isBefore(config.getAllowedCallingEnd());
    }

    /**
     * When this tenant's window next opens: later today if the day has not
     * reached it, otherwise tomorrow morning.
     */
    private Instant nextWindowOpening(ReminderConfiguration config, ZoneId zone) {
        ZonedDateTime now = Instant.now(clock).atZone(zone);
        ZonedDateTime opening = now.with(config.getAllowedCallingStart());
        if (!opening.isAfter(now)) {
            opening = opening.plusDays(1);
        }
        return opening.toInstant();
    }

    private Decision recordAnsweredCall(Reminder reminder, Customer customer, Policy policy, CallResult result) {
        Conversation conversation = new Conversation();
        conversation.setOrganizationId(reminder.getOrganizationId());
        conversation.setCustomerId(customer.getId());
        conversation.setPolicyId(reminder.getPolicyId());
        // The agent who holds the customer, so the call lands in their history
        // even though nobody was on the line.
        conversation.setAgentId(customer.getAssignedAgentId());
        conversation.setReminderId(reminder.getId());
        conversation.setChannel(Domain.Channel.VOICE);
        conversation.setDirection(Domain.ConversationDirection.OUTBOUND);
        conversation.setStatus(Domain.ConversationStatus.COMPLETED);
        conversation.setStartedAt(Instant.now(clock).minusSeconds(result.durationSeconds()));
        conversation.setEndedAt(Instant.now(clock));
        conversation.setDurationSeconds(result.durationSeconds());
        conversation.setSummary("Automated call placed by " + provider.name() + ".");
        conversations.save(conversation);

        Instant at = conversation.getStartedAt();
        for (CallResult.Line line : result.transcript()) {
            ConversationMessage message = new ConversationMessage();
            message.setConversationId(conversation.getId());
            message.setSender(line.sender());
            message.setMessage(line.text());
            message.setTimestamp(at);
            message.setTranscriptReference(result.providerReference());
            messages.save(message);
            // Ordered, so the transcript reads in the sequence it was spoken.
            at = at.plusSeconds(1);
        }

        reminder.setStatus(Domain.ReminderStatus.COMPLETED);
        reminder.setNextAttemptAt(null);
        reminders.save(reminder);

        if (policy != null) {
            log.debug("Call for policy {} recorded as conversation {}", policy.getId(), conversation.getId());
        }
        return Decision.ANSWERED;
    }

    private Decision scheduleRetry(Reminder reminder, Customer customer,
                                   ReminderConfiguration config, CallResult result) {
        if (reminder.getAttemptCount() >= config.getMaxCallAttempts()) {
            return giveUp(reminder, customer,
                    result.outcome() + "_AFTER_" + reminder.getAttemptCount() + "_ATTEMPTS");
        }

        reminder.setStatus(Domain.ReminderStatus.PENDING);
        reminder.setNextAttemptAt(Instant.now(clock).plusSeconds(config.getRetryDelayMinutes() * 60L));
        reminders.save(reminder);
        return Decision.RETRY_SCHEDULED;
    }

    /** Stops trying and tells someone, rather than leaving the work invisible. */
    private Decision giveUp(Reminder reminder, Customer customer, String reason) {
        reminder.setStatus(Domain.ReminderStatus.FAILED);
        reminder.setNextAttemptAt(null);
        reminders.save(reminder);

        UUID assignee = customer.getAssignedAgentId();
        if (assignee != null) {
            HumanTask task = new HumanTask();
            task.setOrganizationId(reminder.getOrganizationId());
            task.setCustomerId(customer.getId());
            task.setPolicyId(reminder.getPolicyId());
            task.setAssignedAgentId(assignee);
            task.setPriority(Domain.HumanTaskPriority.MEDIUM);
            task.setReason("COULD_NOT_REACH_" + reason);
            task.setStatus(Domain.HumanTaskStatus.OPEN);
            humanTasks.save(task);

            audit.record(AuditAction.HUMAN_TASK_RAISED, "HumanTask", task.getId().toString(),
                    reminder.getOrganizationId(), null, provider.name(), reason);
        }
        return Decision.GIVEN_UP;
    }
}
