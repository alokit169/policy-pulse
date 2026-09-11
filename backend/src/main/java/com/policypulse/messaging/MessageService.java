package com.policypulse.messaging;

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
import java.util.Optional;
import java.util.UUID;

/**
 * Sends the message behind an email or SMS reminder, and records that it went.
 *
 * <p>The counterpart to CallService, and deliberately shaped the same way: it
 * runs from the scheduler, so there is no signed-in user, nothing here reaches
 * for a security context, and every record it writes carries the tenant taken
 * from the reminder.
 *
 * <p>Three things are checked before anything is handed to a provider, and a
 * provider is trusted with none of them.
 *
 * <ul>
 *   <li><b>The hour, but only where the hour matters.</b> A text message at three
 *       in the morning wakes somebody up; an email does not. So SMS keeps to the
 *       tenant's calling window, checked in the tenant's own timezone at the
 *       moment of sending, and email is sent whenever it is ready.</li>
 *   <li><b>How many times they have been tried.</b> Past the tenant's limit the
 *       reminder is given up on and a person is told, rather than a customer
 *       being messaged indefinitely — which for SMS is also money.</li>
 *   <li><b>Whether there is an address at all.</b> Email is optional on a
 *       customer, so a reminder set to email somebody who has never given an
 *       address is work nobody can do; it goes to a person instead of failing
 *       quietly for ever.</li>
 * </ul>
 *
 * <p>What was sent is written to the customer's conversation history, so "what
 * have we already said to this person" has one answer covering calls, emails and
 * texts rather than three.
 */
@Service
public class MessageService {
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    public enum Decision {
        /** A provider took it. */
        SENT,
        /** Not sent; another attempt is scheduled. */
        RETRY_SCHEDULED,
        /** Outside the hours this channel may be used. Nothing was sent. */
        OUTSIDE_WINDOW,
        /** No provider for this channel. Nothing was sent and nothing changed. */
        NO_PROVIDER,
        /** Out of attempts, or the address is unusable. A person has been told. */
        GIVEN_UP
    }

    private final MessageProviders providers;
    private final PremiumPaymentRepository premiums;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ReminderRepository reminders;
    private final HumanTaskRepository humanTasks;
    private final OrganizationZones zones;
    private final AuditService audit;
    private final Clock clock;

    public MessageService(MessageProviders providers, PremiumPaymentRepository premiums,
                          ConversationRepository conversations,
                          ConversationMessageRepository messages, ReminderRepository reminders,
                          HumanTaskRepository humanTasks, OrganizationZones zones,
                          AuditService audit, Clock clock) {
        this.providers = providers;
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
    public Decision send(Reminder reminder, Customer customer, Policy policy,
                         ReminderConfiguration config) {
        Optional<MessageProvider> maybeProvider = providers.forChannel(reminder.getChannel());
        if (maybeProvider.isEmpty()) {
            log.debug("Reminder {} is for {}, which has no provider", reminder.getId(), reminder.getChannel());
            return Decision.NO_PROVIDER;
        }
        MessageProvider provider = maybeProvider.get();

        ZoneId zone = zones.zoneOf(reminder.getOrganizationId());
        if (disturbsPeople(reminder.getChannel()) && !insideWindow(config, zone)) {
            // Told when to come back, rather than looked at again every sweep.
            reminder.setNextAttemptAt(nextWindowOpening(config, zone));
            reminders.save(reminder);
            return Decision.OUTSIDE_WINDOW;
        }

        if (reminder.getAttemptCount() >= config.getMaxCallAttempts()) {
            return giveUp(reminder, customer, provider,
                    "NO_ANSWER_AFTER_" + reminder.getAttemptCount() + "_ATTEMPTS");
        }

        String address = addressFor(reminder.getChannel(), customer);
        if (address == null || address.isBlank()) {
            return giveUp(reminder, customer, provider, missingAddressReason(reminder.getChannel()));
        }

        // Sanitised again here, not only where the message is written, so a
        // template added later cannot hand a provider a second line by accident.
        String subject = Sanitised.oneLine(ReminderMessages.subject(reminder, policy));
        String body = ReminderMessages.body(reminder, customer, policy, instalmentFor(reminder));

        SendResult result = provider.send(new OutboundMessage(
                reminder.getChannel(), address, subject, body, reminder.getAttemptCount() + 1));

        // Counted whatever happened, so a provider that always fails still runs
        // out of attempts instead of being retried for ever.
        reminder.setAttemptCount(reminder.getAttemptCount() + 1);
        reminder.setLastAttemptAt(Instant.now(clock));

        audit.record(AuditAction.MESSAGE_SENT, "Reminder", reminder.getId().toString(),
                reminder.getOrganizationId(), null, provider.name(),
                "%s %s attempt=%d".formatted(reminder.getChannel(), result.outcome(),
                        reminder.getAttemptCount()));

        if (result.outcome() == SendResult.Outcome.ACCEPTED) {
            return recordSent(reminder, customer, provider, body, result);
        }
        // Asked of the outcome rather than assumed by a default branch, so an
        // outcome added later is not retried merely for being new.
        if (!result.outcome().worthRetrying()) {
            return giveUp(reminder, customer, provider, result.outcome().name());
        }
        return scheduleRetry(reminder, customer, provider, config, result);
    }

    /**
     * The instalment the reminder names, so the message states the figures the
     * customer is actually being chased for rather than whatever the policy holds
     * as next. Null on reminders raised before that was recorded.
     */
    private PremiumPayment instalmentFor(Reminder reminder) {
        return reminder.getPremiumPaymentId() == null ? null
                : premiums.findById(reminder.getPremiumPaymentId()).orElse(null);
    }

    /**
     * Whether being early or late with this channel disturbs somebody. A text
     * arrives with a noise in the night; an email waits to be opened.
     */
    private boolean disturbsPeople(Domain.Channel channel) {
        return channel == Domain.Channel.SMS || channel == Domain.Channel.WHATSAPP;
    }

    private boolean insideWindow(ReminderConfiguration config, ZoneId zone) {
        LocalTime now = Instant.now(clock).atZone(zone).toLocalTime();
        return !now.isBefore(config.getAllowedCallingStart()) && now.isBefore(config.getAllowedCallingEnd());
    }

    private Instant nextWindowOpening(ReminderConfiguration config, ZoneId zone) {
        ZonedDateTime now = Instant.now(clock).atZone(zone);
        ZonedDateTime opening = now.with(config.getAllowedCallingStart());
        if (!opening.isAfter(now)) {
            opening = opening.plusDays(1);
        }
        return opening.toInstant();
    }

    private String addressFor(Domain.Channel channel, Customer customer) {
        return channel == Domain.Channel.EMAIL ? customer.getEmail() : customer.getPhone();
    }

    private String missingAddressReason(Domain.Channel channel) {
        return channel == Domain.Channel.EMAIL ? "NO_EMAIL_ADDRESS" : "NO_PHONE_NUMBER";
    }

    /**
     * Writes what was sent into the customer's history. A provider accepting it
     * is not the customer reading it, so the record says it was sent and claims
     * nothing more.
     */
    private Decision recordSent(Reminder reminder, Customer customer, MessageProvider provider,
                                String body, SendResult result) {
        Instant now = Instant.now(clock);

        Conversation conversation = new Conversation();
        conversation.setOrganizationId(reminder.getOrganizationId());
        conversation.setCustomerId(customer.getId());
        conversation.setPolicyId(reminder.getPolicyId());
        // The agent who holds the customer, so it lands in their history even
        // though nobody was involved in sending it.
        conversation.setAgentId(customer.getAssignedAgentId());
        conversation.setReminderId(reminder.getId());
        conversation.setChannel(reminder.getChannel());
        conversation.setDirection(Domain.ConversationDirection.OUTBOUND);
        conversation.setStatus(Domain.ConversationStatus.COMPLETED);
        conversation.setStartedAt(now);
        conversation.setEndedAt(now);
        conversation.setSummary("Sent by " + provider.name() + ".");
        conversations.save(conversation);

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversation.getId());
        message.setSender("ASSISTANT");
        message.setMessage(body);
        message.setTimestamp(now);
        message.setTranscriptReference(result.providerReference());
        messages.save(message);

        reminder.setStatus(Domain.ReminderStatus.SENT);
        reminder.setNextAttemptAt(null);
        reminders.save(reminder);
        return Decision.SENT;
    }

    private Decision scheduleRetry(Reminder reminder, Customer customer, MessageProvider provider,
                                   ReminderConfiguration config, SendResult result) {
        if (reminder.getAttemptCount() >= config.getMaxCallAttempts()) {
            return giveUp(reminder, customer, provider,
                    result.outcome() + "_AFTER_" + reminder.getAttemptCount() + "_ATTEMPTS");
        }

        reminder.setStatus(Domain.ReminderStatus.PENDING);
        reminder.setNextAttemptAt(Instant.now(clock).plusSeconds(config.getRetryDelayMinutes() * 60L));
        reminders.save(reminder);
        return Decision.RETRY_SCHEDULED;
    }

    /** Stops trying and tells someone, rather than leaving the work invisible. */
    private Decision giveUp(Reminder reminder, Customer customer, MessageProvider provider, String reason) {
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
