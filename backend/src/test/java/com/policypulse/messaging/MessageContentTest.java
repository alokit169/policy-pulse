package com.policypulse.messaging;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.FixedClockConfiguration;
import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.premiums.PremiumService;
import com.policypulse.reminders.Reminder;
import com.policypulse.reminders.ReminderConfiguration;
import com.policypulse.reminders.ReminderConfigurationRepository;
import com.policypulse.reminders.ReminderDetectionService;
import com.policypulse.reminders.ReminderDispatchService;
import com.policypulse.reminders.ReminderRepository;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a message actually says, which is the part a customer reads and the part
 * an agency is judged on.
 */
class MessageContentTest extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired private ReminderDetectionService detection;
    @Autowired private ReminderDispatchService dispatch;
    @Autowired private ReminderRepository reminders;
    @Autowired private ReminderConfigurationRepository configurations;
    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private PremiumPaymentRepository premiums;
    @Autowired private PremiumService premiumService;
    @Autowired private List<StubMessageProvider> stubs;

    private StubMessageProvider email() {
        return stubs.stream().filter(s -> s.channel() == Domain.Channel.EMAIL).findFirst().orElseThrow();
    }

    private record Scene(Organization org, Customer customer, Policy policy) {
    }

    private Scene given(String policyNumber) {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone("Asia/Kolkata");
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        ReminderConfiguration config = new ReminderConfiguration();
        config.setOrganizationId(org.getId());
        config.setDaysBeforeDue("0");
        config.setDaysAfterDue("");
        config.setAllowedCallingStart(LocalTime.of(9, 0));
        config.setAllowedCallingEnd(LocalTime.of(20, 0));
        config.setMaxCallAttempts(3);
        config.setRetryDelayMinutes(60);
        config.setPreferredChannel(Domain.Channel.EMAIL);
        configurations.save(config);

        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Asha");
        customer.setLastName("Verma");
        customer.setPhone("+9183" + String.format("%08d", Math.floorMod(SEQ.incrementAndGet(), 100_000_000L)));
        customer.setEmail("asha@example.test");
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customer.setCommunicationConsent(true);
        customers.save(customer);

        Policy policy = new Policy();
        policy.setOrganizationId(org.getId());
        policy.setCustomerId(customer.getId());
        policy.setAgentId(agent.getId());
        policy.setPolicyNumber(policyNumber);
        policy.setInsuranceProvider("Example Life");
        policy.setPolicyType("TERM");
        policy.setCurrencyCode("INR");
        policy.setPremiumAmount(new BigDecimal("4500.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policies.save(policy);

        return new Scene(org, customer, policy);
    }

    private PremiumPayment instalment(Scene scene, LocalDate due, Domain.PremiumStatus status) {
        PremiumPayment instalment = new PremiumPayment();
        instalment.setOrganizationId(scene.org().getId());
        instalment.setPolicyId(scene.policy().getId());
        instalment.setAmount(new BigDecimal("4500.00"));
        instalment.setDueDate(due);
        instalment.setStatus(status);
        return premiums.save(instalment);
    }

    private void sendTheOnlyReminder(Scene scene) {
        detection.detectForOrganization(scene.org().getId());
        List<Reminder> raised = reminders
                .findByOrganizationId(scene.org().getId(), PageRequest.of(0, 10)).getContent();
        assertThat(raised).hasSize(1);
        dispatch.dispatch(raised.get(0).getId());
    }

    /**
     * A policy's next premium date is the earliest thing still owing, which is a
     * different question from what this reminder is about. A customer with
     * something unpaid from March would otherwise be told the March date in a
     * message about September's instalment — wrong, in writing, about their money.
     */
    @Test
    void theEmailNamesTheInstalmentTheReminderIsAbout() {
        Scene scene = given("POL-1");
        instalment(scene, LocalDate.of(2026, 3, 1), Domain.PremiumStatus.OVERDUE); // still owing
        instalment(scene, TODAY, Domain.PremiumStatus.DUE);
        premiumService.refreshPolicyDates(policies.findById(scene.policy().getId()).orElseThrow());

        sendTheOnlyReminder(scene);

        String body = email().last().body();
        assertThat(body)
                .as("the reminder is about the instalment due today")
                .contains("15 September 2026")
                .doesNotContain("1 March 2026");
    }

    /**
     * A policy number is whatever the agency typed, and it goes into an email
     * subject. A subject is a header and a header ends at the first line break,
     * so one carrying CRLF would let the rest of the value become headers of its
     * own — a Bcc nobody asked for, from the agency's own domain.
     */
    @Test
    void nothingWithALineBreakInItReachesAProvider() {
        Scene scene = given("POL-2\r\nBcc: everyone@example.test");
        instalment(scene, TODAY, Domain.PremiumStatus.DUE);
        premiumService.refreshPolicyDates(policies.findById(scene.policy().getId()).orElseThrow());

        sendTheOnlyReminder(scene);

        OutboundMessage sent = email().last();
        System.out.println("PROBE B subject = " + sent.subject().replace("\r", "\\r").replace("\n", "\\n"));
        assertThat(sent.subject()).doesNotContain("\r").doesNotContain("\n");
        assertThat(sent.body()).doesNotContain("\r").doesNotContain("\n");
    }
}
