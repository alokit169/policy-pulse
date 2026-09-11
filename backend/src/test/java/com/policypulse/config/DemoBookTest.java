package com.policypulse.config;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.followups.FollowUpEngine;
import com.policypulse.followups.FollowUpRunner;
import com.policypulse.organizations.Organization;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.reminders.ReminderConfiguration;
import com.policypulse.reminders.ReminderConfigurationRepository;
import com.policypulse.reminders.ReminderDetectionService;
import com.policypulse.reminders.ReminderRepository;
import com.policypulse.tasks.HumanTask;
import com.policypulse.tasks.HumanTaskRepository;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo book is what somebody sees first, so it has to be true.
 *
 * <p>A fixture that quietly contradicts itself — a customer described as up to
 * date who is overdue, an opt-out that reminders ignore — teaches whoever is
 * looking at it the wrong thing about the system, and does it convincingly.
 */
class DemoBookTest extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired private DemoBook book;
    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private PremiumPaymentRepository premiums;
    @Autowired private ReminderConfigurationRepository configurations;
    @Autowired private ReminderDetectionService detection;
    @Autowired private ReminderRepository reminders;
    @Autowired private HumanTaskRepository humanTasks;
    @Autowired private FollowUpRunner followUps;

    private Organization org;
    private LocalDate today;

    @BeforeEach
    void seedOneAgency() {
        org = new Organization();
        org.setName("Demo " + UUID.randomUUID());
        org.setTimezone(ZONE.getId());
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

        book.fill(org, List.of(
                createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE),
                createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE)));

        today = zonesToday();
    }

    private LocalDate zonesToday() {
        return LocalDate.ofInstant(com.policypulse.FixedClockConfiguration.FIXED_NOW, ZONE);
    }

    private List<Customer> book() {
        return customers.findAll().stream()
                .filter(c -> c.getOrganizationId().equals(org.getId()))
                .toList();
    }

    private Customer named(String firstName) {
        return book().stream()
                .filter(c -> c.getFirstName().equals(firstName))
                .findFirst().orElseThrow();
    }

    private List<PremiumPayment> instalmentsOf(Customer customer) {
        Policy policy = policies.findAll().stream()
                .filter(p -> p.getCustomerId().equals(customer.getId()))
                .findFirst().orElseThrow();
        return premiums.findByPolicyIdOrderByDueDateAsc(policy.getId());
    }

    private long owedBy(Customer customer) {
        return instalmentsOf(customer).stream()
                .filter(p -> p.getStatus() != Domain.PremiumStatus.PAID)
                .filter(p -> p.getStatus() != Domain.PremiumStatus.WAIVED)
                .filter(p -> !p.getDueDate().isAfter(today))
                .count();
    }

    @Test
    void everybodyGetsACustomerRecordAndAPolicy() {
        assertThat(book()).hasSize(8);
        assertThat(book()).allSatisfy(customer ->
                assertThat(instalmentsOf(customer)).isNotEmpty());
    }

    /** Nothing here can turn into a message to somebody real. */
    @Test
    void nobodyInTheDemoHasAnAddressThatCouldBeDeliveredTo() {
        assertThat(book()).allSatisfy(customer ->
                assertThat(customer.getEmail()).endsWith("@example.test"));
    }

    @Test
    void thePeopleDescribedAsUpToDateOweNothing() {
        assertThat(owedBy(named("Meera"))).isZero();
        assertThat(owedBy(named("Rajesh"))).isZero();
    }

    /**
     * Owing this month is not the same as being behind. A customer with one
     * instalment outstanding, due today, must not show as overdue.
     */
    @Test
    void thePeopleWithThisMonthOutstandingAreNotOverdue() {
        for (String name : List.of("Anita", "Kabir")) {
            assertThat(owedBy(named(name))).isEqualTo(1);
            assertThat(instalmentsOf(named(name)))
                    .filteredOn(p -> p.getDueDate().isBefore(today))
                    .allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(Domain.PremiumStatus.PAID));
        }
    }

    @Test
    void thePeopleDescribedAsBehindAreBehindByAMonthAndNotByYears() {
        for (String name : List.of("Vikram", "Priya", "Arjun", "Sunita")) {
            assertThat(owedBy(named(name)))
                    .as("%s owes two instalments, not a whole policy's worth", name)
                    .isEqualTo(2);
        }
    }

    /** The book is not everybody owing on the same day, which no agency looks like. */
    @Test
    void theOverdueDatesAreNotAllTheSame() {
        List<LocalDate> overdue = book().stream()
                .flatMap(c -> instalmentsOf(c).stream())
                .filter(p -> p.getStatus() == Domain.PremiumStatus.OVERDUE)
                .map(PremiumPayment::getDueDate)
                .distinct()
                .toList();
        assertThat(overdue).hasSizeGreaterThan(1);
    }

    /** The case worth being able to point at, rather than assert. */
    @Test
    void theCustomerWhoOptedOutIsNotChased() {
        Customer sunita = named("Sunita");
        assertThat(sunita.isOptedOut()).isTrue();

        detection.detectForOrganization(org.getId());

        assertThat(reminders.findByOrganizationId(org.getId(), PageRequest.of(0, 50)).getContent())
                .as("she asked not to be contacted")
                .noneSatisfy(r -> assertThat(r.getCustomerId()).isEqualTo(sunita.getId()));
    }

    @Test
    void detectionFindsThePeopleWithSomethingDueToday() {
        detection.detectForOrganization(org.getId());

        assertThat(reminders.findByOrganizationId(org.getId(), PageRequest.of(0, 50)).getContent())
                .as("Anita, Kabir and Priya each have an instalment due today")
                .hasSize(3);
    }

    /** A promise whose day has gone, so the engine has something to do at once. */
    @Test
    void theBrokenPromiseIsEscalatedOnTheFirstRun() {
        FollowUpEngine.Result result = followUps.runFor(org.getId());

        assertThat(result.broughtDue()).isEqualTo(1);
        assertThat(result.escalated()).isEqualTo(1);
        assertThat(openTasks()).anySatisfy(task ->
                assertThat(task.getReason()).isEqualTo("BROKEN_PAYMENT_COMMITMENT"));
    }

    /**
     * The claimed payment is on a different customer from the promise on purpose.
     * On one person they cancel out — correctly, since somebody is already
     * checking — and the demo would show neither.
     */
    @Test
    void theClaimedPaymentIsFlaggedForAPersonAndNotRecordedAsReceived() {
        Customer vikram = named("Vikram");

        // The instalment he says he has paid. His genuinely paid history is not
        // what this is about, and there is plenty of it.
        assertThat(instalmentsOf(vikram))
                .filteredOn(PremiumPayment::isVerificationPending)
                .singleElement()
                .satisfies(claimed -> assertThat(claimed.getStatus())
                        .as("a claim is not a payment")
                        .isEqualTo(Domain.PremiumStatus.OVERDUE));
        assertThat(openTasks()).anySatisfy(task ->
                assertThat(task.getReason()).isEqualTo("VERIFY_CLAIMED_PAYMENT"));
    }

    private List<HumanTask> openTasks() {
        return humanTasks.findByOrganizationIdAndStatusOrderByCreatedAtDesc(
                org.getId(), Domain.HumanTaskStatus.OPEN, PageRequest.of(0, 20)).getContent();
    }
}
