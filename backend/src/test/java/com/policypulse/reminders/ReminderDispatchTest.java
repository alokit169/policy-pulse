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
import com.policypulse.reminders.ReminderDispatchService.Outcome;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery is separate from detection so that a reminder's scheduled moment
 * actually holds it back.
 *
 * <p>The clock is frozen at 06:00 UTC. A tenant on UTC opening at 09:00 is
 * therefore not yet due, while one in Asia/Kolkata opening at 09:00 local
 * (03:30 UTC) already is. That pair makes the schedule testable without waiting.
 */
class ReminderDispatchTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    @Autowired private ReminderDetectionService detection;
    @Autowired private ReminderDispatchService dispatch;
    @Autowired private ReminderRunner runner;
    @Autowired private ReminderRepository reminders;
    @Autowired private ReminderConfigurationRepository configurations;
    @Autowired private PolicyRepository policies;
    @Autowired private CustomerRepository customers;
    @Autowired private PremiumPaymentRepository premiums;
    @Autowired private InAppNotificationRepository notifications;

    private AppUser agentIn(String timezone) {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone(timezone);
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        ReminderConfiguration config = new ReminderConfiguration();
        config.setOrganizationId(org.getId());
        config.setDaysBeforeDue("0");
        config.setDaysAfterDue("");
        config.setAllowedCallingStart(LocalTime.of(9, 0));
        config.setAllowedCallingEnd(LocalTime.of(20, 0));
        config.setPreferredChannel(Domain.Channel.IN_APP);
        configurations.save(config);

        return createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
    }

    private Customer customerFor(AppUser agent) {
        Customer customer = new Customer();
        customer.setOrganizationId(agent.getOrganizationId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Asha");
        customer.setLastName("Verma");
        customer.setPhone("+9192" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customer.setCommunicationConsent(true);
        return customers.save(customer);
    }

    /** Raises exactly one reminder, due today in the tenant's own zone. */
    private Reminder givenAReminderFor(AppUser agent, String timezone, Customer customer) {
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
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policies.save(policy);

        PremiumPayment instalment = new PremiumPayment();
        instalment.setOrganizationId(agent.getOrganizationId());
        instalment.setPolicyId(policy.getId());
        instalment.setAmount(new BigDecimal("1000.00"));
        instalment.setDueDate(LocalDate.ofInstant(FixedClockConfiguration.FIXED_NOW, ZoneId.of(timezone)));
        instalment.setStatus(Domain.PremiumStatus.DUE);
        premiums.save(instalment);

        detection.detectForOrganization(agent.getOrganizationId());
        return reminders.findByOrganizationId(agent.getOrganizationId(), PageRequest.of(0, 10))
                .getContent().get(0);
    }

    private List<InAppNotification> notificationsFor(AppUser agent) {
        return notifications.findByUserIdOrderByCreatedAtDesc(agent.getId(), PageRequest.of(0, 10)).getContent();
    }

    @Test
    void aReminderWhoseMomentHasArrivedIsDeliveredAndMarkedSent() {
        String timezone = "Asia/Kolkata"; // 09:00 local is 03:30 UTC, already past
        AppUser agent = agentIn(timezone);
        Reminder reminder = givenAReminderFor(agent, timezone, customerFor(agent));

        assertThat(dispatch.dispatch(reminder.getId())).isEqualTo(Outcome.SENT);

        Reminder after = reminders.findById(reminder.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.SENT);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getLastAttemptAt()).isEqualTo(FixedClockConfiguration.FIXED_NOW);

        assertThat(notificationsFor(agent))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getTitle()).isEqualTo("Premium due");
                    assertThat(n.getBody()).contains("Asha Verma").contains("1000.00");
                    assertThat(n.isRead()).isFalse();
                });
    }

    /**
     * The point of separating delivery: a tenant whose calling window has not
     * opened yet is left alone rather than being contacted early.
     */
    @Test
    void aReminderScheduledForLaterIsNotDeliveredYet() {
        String timezone = "UTC"; // 09:00 local is 09:00 UTC, still ahead of the frozen 06:00
        AppUser agent = agentIn(timezone);
        Reminder reminder = givenAReminderFor(agent, timezone, customerFor(agent));

        assertThat(dispatch.dueReminderIdsFor(agent.getOrganizationId()))
                .as("not yet inside the calling window")
                .doesNotContain(reminder.getId());

        assertThat(runner.dispatchDueFor(agent.getOrganizationId())).isZero();
        assertThat(reminders.findById(reminder.getId()).orElseThrow().getStatus())
                .isEqualTo(Domain.ReminderStatus.PENDING);
        assertThat(notificationsFor(agent)).isEmpty();
    }

    /**
     * Consent can change between raising a reminder and sending it, so it is
     * checked again at the last moment rather than trusted from detection time.
     */
    @Test
    void optingOutAfterAReminderIsRaisedStopsItBeingSent() {
        String timezone = "Asia/Kolkata";
        AppUser agent = agentIn(timezone);
        Customer customer = customerFor(agent);
        Reminder reminder = givenAReminderFor(agent, timezone, customer);

        customer.setOptedOut(true);
        customers.save(customer);

        assertThat(dispatch.dispatch(reminder.getId())).isEqualTo(Outcome.CANCELLED);

        assertThat(reminders.findById(reminder.getId()).orElseThrow().getStatus())
                .isEqualTo(Domain.ReminderStatus.CANCELLED);
        assertThat(notificationsFor(agent)).isEmpty();
    }

    @Test
    void aPolicyThatLapsesBeforeDeliveryStopsTheReminder() {
        String timezone = "Asia/Kolkata";
        AppUser agent = agentIn(timezone);
        Reminder reminder = givenAReminderFor(agent, timezone, customerFor(agent));

        Policy policy = policies.findById(reminder.getPolicyId()).orElseThrow();
        policy.setStatus(Domain.PolicyStatus.LAPSED);
        policies.save(policy);

        assertThat(dispatch.dispatch(reminder.getId())).isEqualTo(Outcome.CANCELLED);
        assertThat(notificationsFor(agent)).isEmpty();
    }

    /** Delivering twice would mean the agent is told twice about one reminder. */
    @Test
    void aReminderIsOnlyDeliveredOnce() {
        String timezone = "Asia/Kolkata";
        AppUser agent = agentIn(timezone);
        Reminder reminder = givenAReminderFor(agent, timezone, customerFor(agent));

        assertThat(dispatch.dispatch(reminder.getId())).isEqualTo(Outcome.SENT);
        assertThat(dispatch.dispatch(reminder.getId())).isEqualTo(Outcome.DEFERRED);

        assertThat(notificationsFor(agent)).hasSize(1);
        assertThat(reminders.findById(reminder.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void aChannelWithNoProviderIsLeftPendingRatherThanClaimedSent() {
        String timezone = "Asia/Kolkata";
        AppUser agent = agentIn(timezone);
        Reminder reminder = givenAReminderFor(agent, timezone, customerFor(agent));

        reminder.setChannel(Domain.Channel.SMS);
        reminders.save(reminder);

        assertThat(dispatch.dispatch(reminder.getId())).isEqualTo(Outcome.DEFERRED);
        assertThat(reminders.findById(reminder.getId()).orElseThrow().getStatus())
                .as("nothing was sent, so it must not claim to have been")
                .isEqualTo(Domain.ReminderStatus.PENDING);
        assertThat(notificationsFor(agent)).isEmpty();
    }
}
