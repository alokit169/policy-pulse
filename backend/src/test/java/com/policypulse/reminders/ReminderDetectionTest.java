package com.policypulse.reminders;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.FixedClockConfiguration;
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
import com.policypulse.reminders.ReminderDetectionService.DetectionResult;
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
 * Detection runs on a schedule, so the properties that matter are that it can be
 * run again safely and that it reads dates in each tenant's own timezone.
 *
 * <p>The clock is frozen at {@link FixedClockConfiguration#FIXED_NOW}, so what
 * counts as "today" is fixed and the assertions do not drift.
 */
class ReminderDetectionTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    @Autowired private ReminderDetectionService detection;
    @Autowired private ReminderRepository reminders;
    @Autowired private ReminderConfigurationRepository configurations;
    @Autowired private PolicyRepository policies;
    @Autowired private CustomerRepository customers;
    @Autowired private PremiumPaymentRepository premiums;
    @Autowired private InAppNotificationRepository notifications;

    private Organization organizationIn(String timezone) {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone(timezone);
        org.setStatus(Domain.EntityStatus.ACTIVE);
        return organizations.save(org);
    }

    private ReminderConfiguration configure(UUID organizationId, String before, String after) {
        ReminderConfiguration config = new ReminderConfiguration();
        config.setOrganizationId(organizationId);
        config.setDaysBeforeDue(before);
        config.setDaysAfterDue(after);
        config.setAllowedCallingStart(LocalTime.of(9, 0));
        config.setAllowedCallingEnd(LocalTime.of(20, 0));
        config.setPreferredChannel(Domain.Channel.IN_APP);
        return configurations.save(config);
    }

    private Customer customerFor(AppUser agent) {
        Customer customer = new Customer();
        customer.setOrganizationId(agent.getOrganizationId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Asha");
        customer.setLastName("Verma");
        customer.setPhone("+9194" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customer.setCommunicationConsent(true);
        return customers.save(customer);
    }

    private Policy policyFor(AppUser agent, Customer customer, Domain.PolicyStatus status) {
        Policy policy = new Policy();
        policy.setOrganizationId(agent.getOrganizationId());
        policy.setCustomerId(customer.getId());
        policy.setAgentId(agent.getId());
        policy.setPolicyNumber("P-" + UUID.randomUUID().toString().substring(0, 8));
        policy.setInsuranceProvider("Example Life");
        policy.setPolicyType("TERM");
        policy.setCurrencyCode("INR");
        policy.setPremiumAmount(new BigDecimal("1000.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setStatus(status);
        return policies.save(policy);
    }

    private PremiumPayment instalmentDue(Policy policy, LocalDate dueDate, Domain.PremiumStatus status) {
        PremiumPayment instalment = new PremiumPayment();
        instalment.setOrganizationId(policy.getOrganizationId());
        instalment.setPolicyId(policy.getId());
        instalment.setAmount(new BigDecimal("1000.00"));
        instalment.setDueDate(dueDate);
        instalment.setStatus(status);
        return premiums.save(instalment);
    }

    /** Today as the tenant sees it, using the same frozen instant detection uses. */
    private LocalDate todayIn(String timezone) {
        return LocalDate.ofInstant(FixedClockConfiguration.FIXED_NOW, ZoneId.of(timezone));
    }

    private List<Reminder> remindersOf(UUID organizationId) {
        return reminders.findByOrganizationId(organizationId, PageRequest.of(0, 100)).getContent();
    }

    private AppUser agentInOrganizationWith(String timezone) {
        Organization org = organizationIn(timezone);
        return createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
    }

    @Test
    void aPremiumFallingDueAtAConfiguredOffsetRaisesAReminder() {
        AppUser agent = agentInOrganizationWith("UTC");
        configure(agent.getOrganizationId(), "10", "");
        Customer customer = customerFor(agent);
        Policy policy = policyFor(agent, customer, Domain.PolicyStatus.ACTIVE);
        instalmentDue(policy, todayIn("UTC").plusDays(10), Domain.PremiumStatus.UPCOMING);

        DetectionResult result = detection.detectForOrganization(agent.getOrganizationId());

        assertThat(result.created()).isEqualTo(1);
        List<Reminder> raised = remindersOf(agent.getOrganizationId());
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getReminderType()).isEqualTo(Domain.ReminderType.PREMIUM_DUE);
        assertThat(raised.get(0).getStatus()).isEqualTo(Domain.ReminderStatus.PENDING);
        assertThat(raised.get(0).getPolicyId()).isEqualTo(policy.getId());
    }

    /**
     * The property the whole design rests on: the job runs on a timer, so it will
     * be run again over the same data, and must not raise the same reminder twice.
     */
    @Test
    void runningDetectionAgainRaisesNothingNew() {
        AppUser agent = agentInOrganizationWith("UTC");
        configure(agent.getOrganizationId(), "10,5,1,0", "2");
        Customer customer = customerFor(agent);
        Policy policy = policyFor(agent, customer, Domain.PolicyStatus.ACTIVE);
        instalmentDue(policy, todayIn("UTC").plusDays(10), Domain.PremiumStatus.UPCOMING);
        instalmentDue(policy, todayIn("UTC").plusDays(5), Domain.PremiumStatus.UPCOMING);

        DetectionResult first = detection.detectForOrganization(agent.getOrganizationId());
        assertThat(first.created()).isEqualTo(2);

        DetectionResult second = detection.detectForOrganization(agent.getOrganizationId());
        assertThat(second.created()).isZero();
        assertThat(second.skipped()).isEqualTo(2);

        assertThat(remindersOf(agent.getOrganizationId())).hasSize(2);
    }

    @Test
    void anOverdueInstalmentRaisesAnOverdueReminder() {
        AppUser agent = agentInOrganizationWith("UTC");
        configure(agent.getOrganizationId(), "", "2");
        Customer customer = customerFor(agent);
        Policy policy = policyFor(agent, customer, Domain.PolicyStatus.ACTIVE);
        instalmentDue(policy, todayIn("UTC").minusDays(2), Domain.PremiumStatus.OVERDUE);

        detection.detectForOrganization(agent.getOrganizationId());

        assertThat(remindersOf(agent.getOrganizationId()))
                .singleElement()
                .extracting(Reminder::getReminderType)
                .isEqualTo(Domain.ReminderType.PREMIUM_OVERDUE);
    }

    @Test
    void aSettledInstalmentIsNotChased() {
        AppUser agent = agentInOrganizationWith("UTC");
        configure(agent.getOrganizationId(), "0", "");
        Customer customer = customerFor(agent);
        Policy policy = policyFor(agent, customer, Domain.PolicyStatus.ACTIVE);
        instalmentDue(policy, todayIn("UTC"), Domain.PremiumStatus.PAID);
        instalmentDue(policy, todayIn("UTC").plusDays(1), Domain.PremiumStatus.WAIVED);

        detection.detectForOrganization(agent.getOrganizationId());

        assertThat(remindersOf(agent.getOrganizationId())).isEmpty();
    }

    /** Consent is checked before anything is queued, not when a call is placed. */
    @Test
    void aCustomerWhoHasOptedOutIsNeverQueued() {
        AppUser agent = agentInOrganizationWith("UTC");
        configure(agent.getOrganizationId(), "0", "");

        Customer optedOut = customerFor(agent);
        optedOut.setOptedOut(true);
        customers.save(optedOut);
        instalmentDue(policyFor(agent, optedOut, Domain.PolicyStatus.ACTIVE),
                todayIn("UTC"), Domain.PremiumStatus.DUE);

        Customer withheldConsent = customerFor(agent);
        withheldConsent.setCommunicationConsent(false);
        customers.save(withheldConsent);
        instalmentDue(policyFor(agent, withheldConsent, Domain.PolicyStatus.ACTIVE),
                todayIn("UTC"), Domain.PremiumStatus.DUE);

        detection.detectForOrganization(agent.getOrganizationId());

        assertThat(remindersOf(agent.getOrganizationId())).isEmpty();
    }

    @Test
    void aPolicyThatIsNoLongerActiveIsNotChased() {
        AppUser agent = agentInOrganizationWith("UTC");
        configure(agent.getOrganizationId(), "0", "");
        Customer customer = customerFor(agent);
        Policy lapsed = policyFor(agent, customer, Domain.PolicyStatus.LAPSED);
        instalmentDue(lapsed, todayIn("UTC"), Domain.PremiumStatus.DUE);

        detection.detectForOrganization(agent.getOrganizationId());

        assertThat(remindersOf(agent.getOrganizationId())).isEmpty();
    }

    /**
     * Detection decides what needs chasing and when; it does not deliver. Sending
     * here would make the scheduled time decorative, since a reminder raised at
     * four in the morning would reach the agent immediately.
     */
    @Test
    void detectionRaisesTheReminderWithoutDeliveringIt() {
        AppUser agent = agentInOrganizationWith("UTC");
        configure(agent.getOrganizationId(), "0", "");
        Customer customer = customerFor(agent);
        instalmentDue(policyFor(agent, customer, Domain.PolicyStatus.ACTIVE),
                todayIn("UTC"), Domain.PremiumStatus.DUE);

        detection.detectForOrganization(agent.getOrganizationId());

        assertThat(remindersOf(agent.getOrganizationId()))
                .singleElement()
                .extracting(Reminder::getStatus)
                .isEqualTo(Domain.ReminderStatus.PENDING);

        List<InAppNotification> delivered = notifications
                .findByUserIdOrderByCreatedAtDesc(agent.getId(), PageRequest.of(0, 10)).getContent();
        assertThat(delivered).isEmpty();
    }

    /**
     * Reminders are queued for the start of the tenant's calling window, in the
     * tenant's own timezone, so nothing is ever scheduled for the middle of their
     * night.
     */
    @Test
    void aReminderIsQueuedForTheStartOfTheTenantsCallingWindow() {
        String timezone = "Asia/Kolkata";
        AppUser agent = agentInOrganizationWith(timezone);
        configure(agent.getOrganizationId(), "0", "");
        Customer customer = customerFor(agent);
        instalmentDue(policyFor(agent, customer, Domain.PolicyStatus.ACTIVE),
                todayIn(timezone), Domain.PremiumStatus.DUE);

        detection.detectForOrganization(agent.getOrganizationId());

        Reminder raised = remindersOf(agent.getOrganizationId()).get(0);
        ZonedDateTime local = raised.getScheduledAt().atZone(ZoneId.of(timezone));
        assertThat(local.toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(local.toLocalDate()).isEqualTo(todayIn(timezone));
    }

    /**
     * At the frozen instant, Pacific/Midway is still on the previous calendar day
     * from UTC. A tenant there must be evaluated against its own date: reading
     * dates in UTC would chase the wrong day's premiums for part of every day.
     */
    @Test
    void eachTenantIsEvaluatedAgainstItsOwnDate() {
        String behind = "Pacific/Midway";
        assertThat(todayIn(behind))
                .as("the fixed instant must straddle a date boundary for this test to mean anything")
                .isNotEqualTo(todayIn("UTC"));

        AppUser localAgent = agentInOrganizationWith(behind);
        configure(localAgent.getOrganizationId(), "0", "");
        Customer localCustomer = customerFor(localAgent);
        instalmentDue(policyFor(localAgent, localCustomer, Domain.PolicyStatus.ACTIVE),
                todayIn(behind), Domain.PremiumStatus.DUE);

        AppUser utcAgent = agentInOrganizationWith("UTC");
        configure(utcAgent.getOrganizationId(), "0", "");
        Customer utcCustomer = customerFor(utcAgent);
        instalmentDue(policyFor(utcAgent, utcCustomer, Domain.PolicyStatus.ACTIVE),
                todayIn(behind), Domain.PremiumStatus.DUE);

        detection.detectForOrganization(localAgent.getOrganizationId());
        detection.detectForOrganization(utcAgent.getOrganizationId());

        assertThat(remindersOf(localAgent.getOrganizationId()))
                .as("due today where the tenant is")
                .hasSize(1);
        assertThat(remindersOf(utcAgent.getOrganizationId()))
                .as("the same date is not today for a tenant on UTC")
                .isEmpty();
    }

    @Test
    void detectionForOneTenantLeavesAnotherAlone() {
        AppUser first = agentInOrganizationWith("UTC");
        configure(first.getOrganizationId(), "0", "");
        instalmentDue(policyFor(first, customerFor(first), Domain.PolicyStatus.ACTIVE),
                todayIn("UTC"), Domain.PremiumStatus.DUE);

        AppUser second = agentInOrganizationWith("UTC");
        configure(second.getOrganizationId(), "0", "");
        instalmentDue(policyFor(second, customerFor(second), Domain.PolicyStatus.ACTIVE),
                todayIn("UTC"), Domain.PremiumStatus.DUE);

        detection.detectForOrganization(first.getOrganizationId());

        assertThat(remindersOf(first.getOrganizationId())).hasSize(1);
        assertThat(remindersOf(second.getOrganizationId())).isEmpty();
    }

    @Test
    void aTenantWithNoConfigurationGetsTheDefaultsOnFirstRun() {
        AppUser agent = agentInOrganizationWith("UTC");
        Customer customer = customerFor(agent);
        Policy policy = policyFor(agent, customer, Domain.PolicyStatus.ACTIVE);
        // 10 days before due is one of the defaults.
        instalmentDue(policy, todayIn("UTC").plusDays(10), Domain.PremiumStatus.UPCOMING);

        detection.detectForOrganization(agent.getOrganizationId());

        assertThat(configurations.findByOrganizationId(agent.getOrganizationId())).isPresent();
        assertThat(remindersOf(agent.getOrganizationId())).hasSize(1);
    }
}
