package com.policypulse.followups;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.notifications.NotificationService;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.tasks.HumanTask;
import com.policypulse.tasks.HumanTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Works through follow-ups whose moment has come.
 *
 * <p>A commitment recorded and then forgotten is worse than no commitment at
 * all, because everyone believes it is handled. Three things happen here, in
 * this order, and the order is the point.
 *
 * <ul>
 *   <li><b>A promise already kept closes itself.</b> Before anyone is asked to
 *       chase a payment, the books are read. If nothing is owed any more the
 *       follow-up completes on its own. Sending an agent to ring somebody who
 *       has already paid costs the agency the relationship, not just the call.</li>
 *   <li><b>Otherwise it comes due, and a person is told.</b> Until this existed
 *       nothing moved a follow-up out of OPEN, so the dashboard's count of work
 *       past its moment was counting something nobody was ever shown.</li>
 *   <li><b>A promise the day has passed on is escalated, once.</b> Not at the
 *       moment it came due — a customer who said "Friday" has until the end of
 *       Friday — and not again every hour after that.</li>
 * </ul>
 *
 * <p>Each of those three is taken with a conditional update rather than decided
 * from a status read a moment earlier. The hourly sweep and a manager pressing
 * the button can want the same follow-up at the same instant, and a read-then-
 * write lets both through: the agent is told twice and two identical tasks
 * appear in their queue.
 *
 * <p>This reads what is owed and never writes it. A follow-up completing on its
 * own is a statement about work, not about money: the payment it noticed was
 * recorded by a person, through the premium service, before this ever ran.
 *
 * <p>Runs from a scheduler, so there is no signed-in user: nothing here reaches
 * for a security context, and every record it writes carries the tenant taken
 * from the follow-up.
 */
@Service
public class FollowUpEngine {
    private static final Logger log = LoggerFactory.getLogger(FollowUpEngine.class);

    /** One sweep of one tenant never loads more than this. */
    private static final Pageable BATCH = PageRequest.of(0, 200);

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    private static final String SETTLED_NOTE = "Paid before anyone had to chase it.";

    private final FollowUpRepository followUps;
    private final CustomerRepository customers;
    private final PremiumPaymentRepository premiums;
    private final HumanTaskRepository humanTasks;
    private final NotificationService notifications;
    private final OrganizationZones zones;
    private final AuditService audit;
    private final Clock clock;

    public FollowUpEngine(FollowUpRepository followUps, CustomerRepository customers,
                          PremiumPaymentRepository premiums, HumanTaskRepository humanTasks,
                          NotificationService notifications, OrganizationZones zones,
                          AuditService audit, Clock clock) {
        this.followUps = followUps;
        this.customers = customers;
        this.premiums = premiums;
        this.humanTasks = humanTasks;
        this.notifications = notifications;
        this.zones = zones;
        this.audit = audit;
        this.clock = clock;
    }

    /** What one run did. */
    public record Result(int broughtDue, int settled, int escalated) {
        public static final Result NOTHING = new Result(0, 0, 0);

        public Result plus(Result other) {
            return new Result(broughtDue + other.broughtDue,
                    settled + other.settled, escalated + other.escalated);
        }
    }

    /**
     * Follow-ups now worth looking at for one tenant. Ids rather than entities,
     * so the caller can take each one in its own transaction and a single bad
     * row cannot cost an agency the whole sweep.
     */
    @Transactional(readOnly = true)
    public List<UUID> workableIds(UUID organizationId) {
        List<UUID> ids = followUps.findWorkableIds(organizationId, Instant.now(clock), BATCH);
        if (ids.size() == BATCH.getPageSize()) {
            log.warn("Organization {} has at least {} follow-ups waiting; the rest wait for the next sweep",
                    organizationId, ids.size());
        }
        return ids;
    }

    /** One follow-up, in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result workOne(UUID followUpId) {
        FollowUp followUp = followUps.findById(followUpId).orElse(null);
        if (followUp == null) return Result.NOTHING;

        LocalDate today = zones.today(followUp.getOrganizationId());
        Instant now = Instant.now(clock);

        // Read once, up front. Every write below is a conditional update, so this
        // copy is only ever used to decide and to describe, never saved back.
        if (alreadyPaid(followUp, today) && followUps.settle(followUpId, settledNote(followUp)) == 1) {
            audit.record(AuditAction.FOLLOW_UP_COMPLETED, "FollowUp", followUpId.toString(),
                    followUp.getOrganizationId(), null, "follow-up engine", "settled");
            return new Result(0, 1, 0);
        }

        int broughtDue = 0;
        if (followUp.getStatus() == Domain.FollowUpStatus.OPEN && followUps.bringDue(followUpId) == 1) {
            tell(followUp);
            broughtDue = 1;
        }

        int escalated = 0;
        if (promiseBroken(followUp, today) && followUps.claimEscalation(followUpId, now) == 1) {
            escalated = handToAPerson(followUp) ? 1 : 0;
        }
        return new Result(broughtDue, 0, escalated);
    }

    /**
     * Whether the payment this follow-up is about has been made.
     *
     * <p>Only a commitment, and only one tied to a policy: without one there are
     * no books to read, and assuming a promise was kept is the wrong way to be
     * wrong.
     *
     * <p>"Nothing owed" is not the same as "paid". A policy with no schedule at
     * all owes nothing and has been paid nothing, and closing a follow-up over it
     * would put a sentence in the record that is not true. So an instalment has
     * to have existed before its absence from the unpaid list means anything.
     */
    private boolean alreadyPaid(FollowUp followUp, LocalDate today) {
        if (!isPaymentCommitment(followUp) || followUp.getPolicyId() == null) {
            return false;
        }

        // What was owed by the day they promised. A later instalment falling due
        // afterwards is not this promise, and chasing it here would be chasing
        // money that is not late.
        LocalDate promisedFor = followUp.getCommitmentDate() == null ? today : followUp.getCommitmentDate();
        return premiums.countDueOnOrBefore(followUp.getPolicyId(), promisedFor) > 0
                && premiums.countOwedOnOrBefore(followUp.getPolicyId(), promisedFor) == 0;
    }

    /**
     * The day they named has to have passed: somebody who said Friday has all of
     * Friday, and a follow-up comes due at the start of it.
     */
    private boolean promiseBroken(FollowUp followUp, LocalDate today) {
        if (!isPaymentCommitment(followUp) || followUp.getEscalatedAt() != null) {
            return false;
        }

        LocalDate promised = followUp.getCommitmentDate();
        if (promised == null || !promised.isBefore(today)) {
            return false;
        }

        // A payment the customer says they have already made is being checked by
        // somebody. Calling that a broken promise, while a person is looking at
        // it, is how a customer who did pay gets chased anyway.
        return followUp.getPolicyId() == null
                || premiums.countByPolicyIdAndVerificationPendingTrue(followUp.getPolicyId()) == 0;
    }

    /** @return whether a task was actually raised. */
    private boolean handToAPerson(FollowUp followUp) {
        if (followUp.getAssignedAgentId() == null) {
            // The schema requires an assignee, and inventing one would hide the
            // work. The escalation is claimed anyway, or every sweep tries again.
            log.warn("Follow-up {} has no agent to escalate to", followUp.getId());
            return false;
        }

        HumanTask task = new HumanTask();
        task.setOrganizationId(followUp.getOrganizationId());
        task.setCustomerId(followUp.getCustomerId());
        task.setPolicyId(followUp.getPolicyId());
        task.setConversationId(followUp.getConversationId());
        task.setAssignedAgentId(followUp.getAssignedAgentId());
        task.setPriority(Domain.HumanTaskPriority.HIGH);
        task.setReason("BROKEN_PAYMENT_COMMITMENT");
        task.setStatus(Domain.HumanTaskStatus.OPEN);
        humanTasks.save(task);

        audit.record(AuditAction.FOLLOW_UP_ESCALATED, "FollowUp", followUp.getId().toString(),
                followUp.getOrganizationId(), null, "follow-up engine",
                "promised=" + followUp.getCommitmentDate());
        return true;
    }

    /** Tells the agent whose customer this is. */
    private void tell(FollowUp followUp) {
        if (followUp.getAssignedAgentId() == null) {
            log.warn("Follow-up {} came due with nobody assigned to it", followUp.getId());
            return;
        }

        Optional<Customer> customer = customers.findById(followUp.getCustomerId());
        String who = customer.map(Customer::fullName).orElse("A customer");

        // Not "promised today". A sweep can be late, and a follow-up raised for a
        // day already gone would then say something the reader can see is wrong,
        // which is the fastest way to have notifications ignored. The date is in
        // the body, where it is true whenever it is read.
        String title = isPaymentCommitment(followUp) ? "Payment promised" : "Follow-up due";
        String body = isPaymentCommitment(followUp) && followUp.getCommitmentDate() != null
                ? "%s said they would pay on %s.".formatted(who, DAY.format(followUp.getCommitmentDate()))
                : "%s needs following up (%s).".formatted(who, followUp.getReason());

        notifications.notifyUser(followUp.getOrganizationId(), followUp.getAssignedAgentId(), title, body);
    }

    private String settledNote(FollowUp followUp) {
        String existing = followUp.getNotes();
        return existing == null || existing.isBlank() ? SETTLED_NOTE : existing + "\n" + SETTLED_NOTE;
    }

    private boolean isPaymentCommitment(FollowUp followUp) {
        return FollowUpReason.PAYMENT_COMMITMENT.name().equals(followUp.getReason());
    }
}
