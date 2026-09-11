package com.policypulse.followups;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.notifications.InAppNotification;
import com.policypulse.notifications.InAppNotificationRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.tasks.HumanTask;
import com.policypulse.tasks.HumanTaskRepository;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine that makes a recorded commitment mean something.
 *
 * <p>The clock is frozen at 06:00 UTC on 15 September 2026, which is 11:30 the
 * same day in Asia/Kolkata. So for a tenant there, a commitment for the 14th is
 * a day gone by, one for the 15th is still today, and one for the 16th has not
 * arrived. That is the whole calendar these tests need.
 */
class FollowUpEngineTest extends AbstractIntegrationTest {

    private static final ZoneId TENANT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 9, 16);

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired private FollowUpRunner runner;
    @Autowired private FollowUpRepository followUps;
    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private PremiumPaymentRepository premiums;
    @Autowired private HumanTaskRepository humanTasks;
    @Autowired private InAppNotificationRepository notifications;

    private record Scene(Organization org, AppUser agent, Customer customer, Policy policy) {
    }

    private Scene given() {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone(TENANT_ZONE.getId());
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Meera");
        customer.setLastName("Nair");
        customer.setPhone("+9188" + String.format("%08d", Math.floorMod(SEQ.incrementAndGet(), 100_000_000L)));
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customer.setCommunicationConsent(true);
        customers.save(customer);

        Policy policy = new Policy();
        policy.setOrganizationId(org.getId());
        policy.setCustomerId(customer.getId());
        policy.setAgentId(agent.getId());
        policy.setPolicyNumber("P-" + UUID.randomUUID().toString().substring(0, 8));
        policy.setInsuranceProvider("Example Life");
        policy.setPolicyType("TERM");
        policy.setCurrencyCode("INR");
        policy.setPremiumAmount(new BigDecimal("4500.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policies.save(policy);

        return new Scene(org, agent, customer, policy);
    }

    private PremiumPayment instalment(Scene scene, LocalDate dueDate, Domain.PremiumStatus status) {
        PremiumPayment instalment = new PremiumPayment();
        instalment.setOrganizationId(scene.org().getId());
        instalment.setPolicyId(scene.policy().getId());
        instalment.setAmount(new BigDecimal("4500.00"));
        instalment.setDueDate(dueDate);
        instalment.setStatus(status);
        return premiums.save(instalment);
    }

    /** A commitment recorded the way the assistant or an agent records one. */
    private FollowUp commitment(Scene scene, LocalDate promisedFor, boolean withPolicy) {
        FollowUp followUp = new FollowUp();
        followUp.setOrganizationId(scene.org().getId());
        followUp.setCustomerId(scene.customer().getId());
        followUp.setPolicyId(withPolicy ? scene.policy().getId() : null);
        followUp.setAssignedAgentId(scene.agent().getId());
        followUp.setReason(FollowUpReason.PAYMENT_COMMITMENT.name());
        followUp.setCommitmentDate(promisedFor);
        followUp.setDueAt(ZonedDateTime.of(promisedFor, LocalTime.of(9, 0), TENANT_ZONE).toInstant());
        followUp.setStatus(Domain.FollowUpStatus.OPEN);
        return followUps.save(followUp);
    }

    private FollowUp reloaded(FollowUp followUp) {
        return followUps.findById(followUp.getId()).orElseThrow();
    }

    private List<InAppNotification> notificationsFor(Scene scene) {
        return notifications.findByUserIdOrderByCreatedAtDesc(
                scene.agent().getId(), PageRequest.of(0, 10)).getContent();
    }

    private List<HumanTask> tasksFor(Scene scene) {
        return humanTasks.findByOrganizationIdAndStatusOrderByCreatedAtDesc(
                scene.org().getId(), Domain.HumanTaskStatus.OPEN, PageRequest.of(0, 10)).getContent();
    }

    /**
     * Until now nothing ever moved a follow-up out of OPEN, so the dashboard's
     * count of work past its moment was counting something nobody was shown.
     */
    @Test
    void aCommitmentThatHasComeDueIsMarkedDueAndTheAgentIsTold() {
        Scene scene = given();
        instalment(scene, TODAY, Domain.PremiumStatus.DUE);
        FollowUp followUp = commitment(scene, TODAY, true);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result.broughtDue()).isEqualTo(1);
        assertThat(reloaded(followUp).getStatus()).isEqualTo(Domain.FollowUpStatus.DUE);
        assertThat(notificationsFor(scene))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getTitle()).isEqualTo("Payment promised");
                    assertThat(n.getBody()).contains("Meera Nair").contains("15 September");
                });
    }

    /** The sweep runs hourly, so being told once has to mean once. */
    @Test
    void aSweepThatRunsAgainDoesNotTellAnybodyTwice() {
        Scene scene = given();
        instalment(scene, TODAY, Domain.PremiumStatus.DUE);
        commitment(scene, TODAY, true);

        runner.runFor(scene.org().getId());
        FollowUpEngine.Result second = runner.runFor(scene.org().getId());

        assertThat(second.broughtDue()).isZero();
        assertThat(notificationsFor(scene)).hasSize(1);
    }

    /**
     * The behaviour worth having. Sending an agent to ring somebody who has
     * already paid costs the agency the relationship, not just the call.
     */
    @Test
    void aPromiseAlreadyKeptClosesItselfAndNobodyIsAskedToChaseIt() {
        Scene scene = given();
        instalment(scene, TODAY, Domain.PremiumStatus.PAID);
        FollowUp followUp = commitment(scene, TODAY, true);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result.settled()).isEqualTo(1);
        assertThat(result.broughtDue()).as("nobody had to be told about it").isZero();

        FollowUp after = reloaded(followUp);
        assertThat(after.getStatus()).isEqualTo(Domain.FollowUpStatus.COMPLETED);
        assertThat(after.getNotes()).contains("Paid before anyone had to chase it");
        assertThat(notificationsFor(scene)).isEmpty();
    }

    /** A waived instalment is settled too. Nothing is owed, so nothing is chased. */
    @Test
    void aWaivedInstalmentAlsoCountsAsNothingOwed() {
        Scene scene = given();
        instalment(scene, TODAY, Domain.PremiumStatus.WAIVED);
        FollowUp followUp = commitment(scene, TODAY, true);

        runner.runFor(scene.org().getId());

        assertThat(reloaded(followUp).getStatus()).isEqualTo(Domain.FollowUpStatus.COMPLETED);
    }

    /**
     * A later instalment is not the promise. Chasing it here would be chasing
     * money that is not late yet.
     */
    @Test
    void anInstalmentFallingDueAfterThePromiseIsNotWhatWasPromised() {
        Scene scene = given();
        instalment(scene, TODAY, Domain.PremiumStatus.PAID);
        instalment(scene, TOMORROW.plusYears(1), Domain.PremiumStatus.UPCOMING);
        FollowUp followUp = commitment(scene, TODAY, true);

        runner.runFor(scene.org().getId());

        assertThat(reloaded(followUp).getStatus())
                .as("the promise was about what was owed by the day they named")
                .isEqualTo(Domain.FollowUpStatus.COMPLETED);
    }

    /** Somebody who said Friday has all of Friday. */
    @Test
    void apromiseForTodayIsNotYetBroken() {
        Scene scene = given();
        instalment(scene, TODAY, Domain.PremiumStatus.DUE);
        FollowUp followUp = commitment(scene, TODAY, true);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result.escalated()).isZero();
        assertThat(reloaded(followUp).getEscalatedAt()).isNull();
        assertThat(tasksFor(scene)).isEmpty();
    }

    @Test
    void aPromiseTheDayHasPassedOnIsHandedToAPerson() {
        Scene scene = given();
        instalment(scene, YESTERDAY, Domain.PremiumStatus.OVERDUE);
        FollowUp followUp = commitment(scene, YESTERDAY, true);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result.escalated()).isEqualTo(1);
        assertThat(reloaded(followUp).getEscalatedAt()).isNotNull();
        assertThat(tasksFor(scene))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.getReason()).isEqualTo("BROKEN_PAYMENT_COMMITMENT");
                    assertThat(task.getAssignedAgentId()).isEqualTo(scene.agent().getId());
                    assertThat(task.getPriority()).isEqualTo(Domain.HumanTaskPriority.HIGH);
                });
    }

    @Test
    void abrokenPromiseIsHandedOverOnceRatherThanEveryHour() {
        Scene scene = given();
        instalment(scene, YESTERDAY, Domain.PremiumStatus.OVERDUE);
        commitment(scene, YESTERDAY, true);

        runner.runFor(scene.org().getId());
        runner.runFor(scene.org().getId());
        runner.runFor(scene.org().getId());

        assertThat(tasksFor(scene)).hasSize(1);
    }

    /**
     * The Phase 8 rule reaching forward: a customer saying they have paid puts
     * the instalment in front of a person. Calling that a broken promise while
     * somebody is checking it is how a customer who did pay gets chased anyway.
     */
    @Test
    void aClaimedPaymentSomebodyIsCheckingIsNotCalledABrokenPromise() {
        Scene scene = given();
        PremiumPayment instalment = instalment(scene, YESTERDAY, Domain.PremiumStatus.OVERDUE);
        instalment.setVerificationPending(true);
        premiums.save(instalment);

        FollowUp followUp = commitment(scene, YESTERDAY, true);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result.escalated()).isZero();
        assertThat(tasksFor(scene)).isEmpty();
        assertThat(reloaded(followUp).getStatus())
                .as("still outstanding, just not escalated")
                .isEqualTo(Domain.FollowUpStatus.DUE);
    }

    /**
     * With no policy there are no books to read, so the promise cannot be shown
     * to have been kept. Escalating is the safe direction to be wrong in.
     */
    @Test
    void aCommitmentWithNoPolicyIsEscalatedRatherThanAssumedKept() {
        Scene scene = given();
        FollowUp followUp = commitment(scene, YESTERDAY, false);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result.settled()).isZero();
        assertThat(result.escalated()).isEqualTo(1);
        assertThat(reloaded(followUp).getStatus()).isEqualTo(Domain.FollowUpStatus.DUE);
    }

    @Test
    void aFollowUpWhoseMomentHasNotArrivedIsLeftAlone() {
        Scene scene = given();
        instalment(scene, TOMORROW, Domain.PremiumStatus.UPCOMING);
        FollowUp followUp = commitment(scene, TOMORROW, true);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result).isEqualTo(new FollowUpEngine.Result(0, 0, 0));
        assertThat(reloaded(followUp).getStatus()).isEqualTo(Domain.FollowUpStatus.OPEN);
        assertThat(notificationsFor(scene)).isEmpty();
    }

    @Test
    void aFollowUpSomebodyHasAlreadyClosedIsNotReopened() {
        Scene scene = given();
        instalment(scene, YESTERDAY, Domain.PremiumStatus.OVERDUE);
        FollowUp followUp = commitment(scene, YESTERDAY, true);
        followUp.setStatus(Domain.FollowUpStatus.CANCELLED);
        followUps.save(followUp);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result).isEqualTo(new FollowUpEngine.Result(0, 0, 0));
        assertThat(reloaded(followUp).getStatus()).isEqualTo(Domain.FollowUpStatus.CANCELLED);
    }

    /** A run for one tenant must not touch another's work. */
    @Test
    void aRunForOneTenantLeavesAnothersFollowUpsAlone() {
        Scene ours = given();
        Scene theirs = given();
        instalment(ours, YESTERDAY, Domain.PremiumStatus.OVERDUE);
        instalment(theirs, YESTERDAY, Domain.PremiumStatus.OVERDUE);
        commitment(ours, YESTERDAY, true);
        FollowUp other = commitment(theirs, YESTERDAY, true);

        runner.runFor(ours.org().getId());

        assertThat(reloaded(other).getStatus()).isEqualTo(Domain.FollowUpStatus.OPEN);
        assertThat(tasksFor(theirs)).isEmpty();
        assertThat(notificationsFor(theirs)).isEmpty();
    }

    /**
     * A sweep can be late, or a promise can be recorded for a day already gone.
     * A notification that says "today" about the tenth, read on the eleventh, is
     * the fastest way to have notifications ignored.
     */
    @Test
    void aLateNotificationDoesNotClaimThePromiseWasForToday() {
        Scene scene = given();
        instalment(scene, YESTERDAY, Domain.PremiumStatus.OVERDUE);
        commitment(scene, YESTERDAY, true);

        runner.runFor(scene.org().getId());

        assertThat(notificationsFor(scene))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getTitle()).doesNotContain("today");
                    assertThat(n.getBody()).contains("14 September");
                });
    }

    /**
     * "Nothing owed" is not "paid". A policy with no schedule owes nothing and
     * has been paid nothing, and closing a follow-up over it would put a sentence
     * in the record that is not true.
     */
    @Test
    void aPolicyWithNothingToPayIsNotCalledPaid() {
        Scene scene = given(); // a policy, but no instalments on it at all
        FollowUp followUp = commitment(scene, TODAY, true);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result.settled()).isZero();
        FollowUp after = reloaded(followUp);
        assertThat(after.getStatus()).isEqualTo(Domain.FollowUpStatus.DUE);
        assertThat(after.getNotes()).isNull();
    }

    /**
     * The engine watches a follow-up until it has either closed it or handed it
     * to a person, and then stops. Keeping announced work in the queue means an
     * agency that lets its follow-ups pile up eventually fills the batch with
     * work nothing can act on, and never sees a new one come due at all.
     */
    @Test
    void anUnattendedBacklogDoesNotCrowdOutSomethingNew() {
        Scene scene = given();

        // More than one sweep can carry, every one of them already handed over
        // and every one older than the follow-up that matters.
        for (int i = 0; i < 250; i++) {
            FollowUp stale = commitment(scene, YESTERDAY.minusDays(i + 2), true);
            stale.setStatus(Domain.FollowUpStatus.DUE);
            stale.setEscalatedAt(java.time.Instant.now());
            followUps.save(stale);
        }

        FollowUp fresh = commitment(scene, TODAY, true);
        instalment(scene, TODAY, Domain.PremiumStatus.DUE);

        runner.runFor(scene.org().getId());

        assertThat(reloaded(fresh).getStatus()).isEqualTo(Domain.FollowUpStatus.DUE);
    }

    /** A non-payment follow-up has no books to read and no promise to break. */
    @Test
    void aCallbackRequestComesDueButIsNeverEscalated() {
        Scene scene = given();
        FollowUp followUp = new FollowUp();
        followUp.setOrganizationId(scene.org().getId());
        followUp.setCustomerId(scene.customer().getId());
        followUp.setAssignedAgentId(scene.agent().getId());
        followUp.setReason(FollowUpReason.CALLBACK_REQUESTED.name());
        followUp.setDueAt(ZonedDateTime.of(YESTERDAY, LocalTime.of(9, 0), TENANT_ZONE).toInstant());
        followUp.setStatus(Domain.FollowUpStatus.OPEN);
        followUps.save(followUp);

        FollowUpEngine.Result result = runner.runFor(scene.org().getId());

        assertThat(result.broughtDue()).isEqualTo(1);
        assertThat(result.escalated()).isZero();
        assertThat(notificationsFor(scene))
                .singleElement()
                .satisfies(n -> assertThat(n.getTitle()).isEqualTo("Follow-up due"));
    }
}
