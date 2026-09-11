package com.policypulse.ai;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.Domain;
import com.policypulse.conversations.Conversation;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.followups.FollowUp;
import com.policypulse.followups.FollowUpReason;
import com.policypulse.followups.FollowUpRepository;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.reminders.Reminder;
import com.policypulse.reminders.ReminderRepository;
import com.policypulse.tasks.HumanTask;
import com.policypulse.tasks.HumanTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Decides what a model's reading of a call is allowed to cause.
 *
 * <p>Everything the assistant produces arrives here before anything is written.
 * Three rules shape it.
 *
 * <p><b>It never records money as received.</b> A customer saying they have paid
 * is a claim, not a receipt. The instalment is flagged for verification and a
 * person is asked to check it against the books; the status stays exactly as it
 * was. Marking it paid on the strength of a sentence would let a
 * misheard call write off a debt, and nothing downstream would ever notice.
 *
 * <p><b>Below the acting threshold it does nothing but ask for help.</b> A weak
 * reading raises work for a person instead of guessing.
 *
 * <p><b>What it may do on its own only ever reduces what the system does.</b>
 * Stopping contact after an opt-out and scheduling a callback are safe to get
 * wrong in the direction they fail: the cost is a missed reminder, not a wrong
 * balance.
 */
@Service
public class ActionValidationService {
    private static final Logger log = LoggerFactory.getLogger(ActionValidationService.class);

    /** Below this, nothing is acted on; a person is asked instead. */
    static final double ACTING_THRESHOLD = 0.7;

    /** Higher bar for anything that changes a customer's own record. */
    static final double CONSENT_THRESHOLD = 0.9;

    private static final LocalTime START_OF_WORKING_DAY = LocalTime.of(9, 0);

    private static final Set<Domain.PremiumStatus> SETTLED =
            EnumSet.of(Domain.PremiumStatus.PAID, Domain.PremiumStatus.WAIVED);

    private final CustomerRepository customers;
    private final PremiumPaymentRepository premiums;
    private final FollowUpRepository followUps;
    private final HumanTaskRepository humanTasks;
    private final ReminderRepository reminders;
    private final OrganizationZones zones;
    private final AuditService audit;
    private final Clock clock;

    public ActionValidationService(CustomerRepository customers, PremiumPaymentRepository premiums,
                                   FollowUpRepository followUps, HumanTaskRepository humanTasks,
                                   ReminderRepository reminders, OrganizationZones zones,
                                   AuditService audit, Clock clock) {
        this.customers = customers;
        this.premiums = premiums;
        this.followUps = followUps;
        this.humanTasks = humanTasks;
        this.reminders = reminders;
        this.zones = zones;
        this.audit = audit;
        this.clock = clock;
    }

    /** What was decided, and what it caused. Returned so it can be shown and audited. */
    public record Outcome(Domain.AiIntent intent, double confidence, boolean acted,
                          String decision, List<String> actions) {
    }

    @Transactional
    public Outcome apply(Conversation conversation, IntentAnalysis analysis) {
        List<String> actions = new ArrayList<>();

        Optional<Customer> maybeCustomer = customers.findById(conversation.getCustomerId());
        if (maybeCustomer.isEmpty()) {
            return new Outcome(analysis.intent(), analysis.confidence(), false,
                    "The customer this call belongs to no longer exists.", actions);
        }
        Customer customer = maybeCustomer.get();

        if (analysis.confidence() < ACTING_THRESHOLD) {
            raiseTask(conversation, customer, "LOW_CONFIDENCE_" + analysis.intent(),
                    Domain.HumanTaskPriority.MEDIUM, actions);
            return new Outcome(analysis.intent(), analysis.confidence(), false,
                    "Not confident enough to act on; passed to a person.", actions);
        }

        return switch (analysis.intent()) {
            case PAYMENT_CONFIRMED -> paymentClaimed(conversation, customer, actions, analysis);
            case PAYMENT_COMMITMENT -> commitment(conversation, customer, analysis, actions);
            case OPT_OUT -> optOut(conversation, customer, analysis, actions);
            case CALL_LATER -> callback(conversation, customer, actions);
            case CANNOT_PAY, PAYMENT_DELAYED, NO_LONGER_INTERESTED ->
                    handOver(conversation, customer, analysis, Domain.HumanTaskPriority.HIGH, actions);
            case REQUEST_HUMAN_AGENT ->
                    handOver(conversation, customer, analysis, Domain.HumanTaskPriority.URGENT, actions);
            case WRONG_NUMBER ->
                    handOver(conversation, customer, analysis, Domain.HumanTaskPriority.MEDIUM, actions);
            case DOCUMENT_REQUEST, POLICY_QUERY, MATURITY_QUERY, BONUS_QUERY, GENERAL_QUERY ->
                    handOver(conversation, customer, analysis, Domain.HumanTaskPriority.LOW, actions);
            case UNKNOWN -> new Outcome(analysis.intent(), analysis.confidence(), false,
                    "Nothing recognisable was said.", actions);
        };
    }

    /**
     * The rule the whole design exists for. A customer saying they have paid does
     * not make it so, and the instalment's status is left untouched. It is flagged
     * for verification and a person checks it against what was actually received.
     */
    private Outcome paymentClaimed(Conversation conversation, Customer customer,
                                   List<String> actions, IntentAnalysis analysis) {
        Optional<PremiumPayment> oldestUnsettled = oldestUnsettledInstalment(conversation);

        oldestUnsettled.ifPresent(instalment -> {
            instalment.setVerificationPending(true);
            premiums.save(instalment);
            actions.add("PREMIUM_FLAGGED_FOR_VERIFICATION");

            audit.record(AuditAction.PREMIUM_VERIFICATION_REQUESTED, "PremiumPayment",
                    instalment.getId().toString(), conversation.getOrganizationId(), null,
                    "assistant", "claimed during conversation " + conversation.getId());
        });

        raiseTask(conversation, customer, "VERIFY_CLAIMED_PAYMENT",
                Domain.HumanTaskPriority.HIGH, actions);

        return new Outcome(analysis.intent(), analysis.confidence(), true,
                oldestUnsettled.isPresent()
                        ? "Payment claimed. The instalment is flagged for checking; its status is unchanged."
                        : "Payment claimed, but nothing is outstanding. Passed to a person.",
                actions);
    }

    /** A promise with a usable date becomes a follow-up; without one, a person decides. */
    private Outcome commitment(Conversation conversation, Customer customer,
                               IntentAnalysis analysis, List<String> actions) {
        LocalDate today = zones.today(conversation.getOrganizationId());
        LocalDate promised = analysis.committedDate();

        if (promised == null || promised.isBefore(today)) {
            raiseTask(conversation, customer, "COMMITMENT_WITHOUT_A_USABLE_DATE",
                    Domain.HumanTaskPriority.MEDIUM, actions);
            return new Outcome(analysis.intent(), analysis.confidence(), false,
                    "A promise was heard but no usable date; passed to a person.", actions);
        }

        FollowUp followUp = new FollowUp();
        followUp.setOrganizationId(conversation.getOrganizationId());
        followUp.setCustomerId(customer.getId());
        followUp.setPolicyId(conversation.getPolicyId());
        followUp.setConversationId(conversation.getId());
        // The customer's own agent owns it, not whoever or whatever took the call.
        followUp.setAssignedAgentId(customer.getAssignedAgentId());
        followUp.setReason(FollowUpReason.PAYMENT_COMMITMENT.name());
        followUp.setCommitmentDate(promised);
        followUp.setDueAt(ZonedDateTime
                .of(promised, START_OF_WORKING_DAY, zones.zoneOf(conversation.getOrganizationId()))
                .toInstant());
        followUp.setStatus(Domain.FollowUpStatus.OPEN);
        followUps.save(followUp);
        actions.add("FOLLOW_UP_CREATED");

        audit.record(AuditAction.FOLLOW_UP_CREATED, "FollowUp", followUp.getId().toString(),
                conversation.getOrganizationId(), null, "assistant",
                "from conversation " + conversation.getId());

        return new Outcome(analysis.intent(), analysis.confidence(), true,
                "Customer promised to pay on " + promised + "; a follow-up was created.", actions);
    }

    /**
     * Acting on this reduces what the system does, so it is safe in the direction
     * it fails. It still needs near-certainty, because reversing it means asking
     * the customer again.
     */
    private Outcome optOut(Conversation conversation, Customer customer,
                           IntentAnalysis analysis, List<String> actions) {
        if (analysis.confidence() < CONSENT_THRESHOLD) {
            raiseTask(conversation, customer, "POSSIBLE_OPT_OUT",
                    Domain.HumanTaskPriority.HIGH, actions);
            return new Outcome(analysis.intent(), analysis.confidence(), false,
                    "An opt-out was heard but not clearly enough to act on.", actions);
        }

        customer.setOptedOut(true);
        customer.setCommunicationConsent(false);
        customers.save(customer);
        actions.add("CUSTOMER_OPTED_OUT");

        // Anything already queued for them is dropped, or the opt-out would only
        // take effect once the backlog had gone out.
        int cancelled = 0;
        for (Reminder reminder : reminders.findByCustomerIdOrderByScheduledAtDesc(customer.getId())) {
            if (reminder.getStatus() == Domain.ReminderStatus.PENDING) {
                reminder.setStatus(Domain.ReminderStatus.CANCELLED);
                reminders.save(reminder);
                cancelled++;
            }
        }
        if (cancelled > 0) actions.add("QUEUED_REMINDERS_CANCELLED_" + cancelled);

        audit.record(AuditAction.CUSTOMER_OPTED_OUT, "Customer", customer.getId().toString(),
                conversation.getOrganizationId(), null, "assistant",
                "from conversation " + conversation.getId());

        return new Outcome(analysis.intent(), analysis.confidence(), true,
                "Customer asked not to be contacted. Consent withdrawn and queued reminders dropped.",
                actions);
    }

    private Outcome callback(Conversation conversation, Customer customer, List<String> actions) {
        FollowUp followUp = new FollowUp();
        followUp.setOrganizationId(conversation.getOrganizationId());
        followUp.setCustomerId(customer.getId());
        followUp.setPolicyId(conversation.getPolicyId());
        followUp.setConversationId(conversation.getId());
        followUp.setAssignedAgentId(customer.getAssignedAgentId());
        followUp.setReason(FollowUpReason.CALLBACK_REQUESTED.name());
        followUp.setDueAt(Instant.now(clock));
        followUp.setStatus(Domain.FollowUpStatus.OPEN);
        followUps.save(followUp);
        actions.add("FOLLOW_UP_CREATED");

        return new Outcome(Domain.AiIntent.CALL_LATER, 1.0, true,
                "Customer asked to be called back; a follow-up was created.", actions);
    }

    /** Everything the assistant is not allowed to settle goes to a person. */
    private Outcome handOver(Conversation conversation, Customer customer, IntentAnalysis analysis,
                             Domain.HumanTaskPriority priority, List<String> actions) {
        raiseTask(conversation, customer, analysis.intent().name(), priority, actions);
        return new Outcome(analysis.intent(), analysis.confidence(), true,
                "Passed to a person to handle.", actions);
    }

    private void raiseTask(Conversation conversation, Customer customer, String reason,
                           Domain.HumanTaskPriority priority, List<String> actions) {
        UUID assignee = customer.getAssignedAgentId() != null
                ? customer.getAssignedAgentId()
                : conversation.getAgentId();

        if (assignee == null) {
            // The schema requires an assignee, and inventing one would hide the work.
            log.warn("No agent to assign a task to for conversation {}", conversation.getId());
            return;
        }

        HumanTask task = new HumanTask();
        task.setOrganizationId(conversation.getOrganizationId());
        task.setCustomerId(customer.getId());
        task.setPolicyId(conversation.getPolicyId());
        task.setConversationId(conversation.getId());
        task.setAssignedAgentId(assignee);
        task.setPriority(priority);
        task.setReason(reason);
        task.setStatus(Domain.HumanTaskStatus.OPEN);
        humanTasks.save(task);
        actions.add("HUMAN_TASK_RAISED");

        audit.record(AuditAction.HUMAN_TASK_RAISED, "HumanTask", task.getId().toString(),
                conversation.getOrganizationId(), null, "assistant", reason);
    }

    /** The instalment a claim would be about: the oldest one still owing. */
    private Optional<PremiumPayment> oldestUnsettledInstalment(Conversation conversation) {
        if (conversation.getPolicyId() == null) return Optional.empty();

        return premiums.findByPolicyIdOrderByDueDateAsc(conversation.getPolicyId()).stream()
                .filter(p -> !SETTLED.contains(p.getStatus()))
                .min(Comparator.comparing(PremiumPayment::getDueDate));
    }
}
