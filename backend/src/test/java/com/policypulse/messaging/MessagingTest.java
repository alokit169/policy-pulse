package com.policypulse.messaging;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.FixedClockConfiguration;
import com.policypulse.common.Domain;
import com.policypulse.conversations.Conversation;
import com.policypulse.conversations.ConversationMessage;
import com.policypulse.conversations.ConversationMessageRepository;
import com.policypulse.conversations.ConversationRepository;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.reminders.Reminder;
import com.policypulse.reminders.ReminderConfiguration;
import com.policypulse.reminders.ReminderConfigurationRepository;
import com.policypulse.reminders.ReminderDispatchService;
import com.policypulse.reminders.ReminderRepository;
import com.policypulse.tasks.HumanTask;
import com.policypulse.tasks.HumanTaskRepository;
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
 * Sending email and SMS, and the rules that decide whether one may be sent.
 *
 * <p>The clock is frozen at 06:00 UTC. A tenant on UTC whose window opens at
 * 09:00 is therefore shut, while one in Asia/Kolkata is at 11:30 and open. That
 * pair makes the hour testable without waiting for one.
 */
class MessagingTest extends AbstractIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired private ReminderDispatchService dispatch;
    @Autowired private ReminderRepository reminders;
    @Autowired private ReminderConfigurationRepository configurations;
    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private ConversationRepository conversations;
    @Autowired private ConversationMessageRepository messages;
    @Autowired private HumanTaskRepository humanTasks;
    @Autowired private List<StubMessageProvider> stubs;

    private StubMessageProvider stub(Domain.Channel channel) {
        return stubs.stream().filter(s -> s.channel() == channel).findFirst().orElseThrow();
    }

    private record Scene(Organization org, AppUser agent, Customer customer, Reminder reminder) {
    }

    /** A tenant, a customer who consents, and one reminder already due. */
    private Scene given(String timezone, Domain.Channel channel, String email) {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone(timezone);
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        ReminderConfiguration config = new ReminderConfiguration();
        config.setOrganizationId(org.getId());
        config.setAllowedCallingStart(LocalTime.of(9, 0));
        config.setAllowedCallingEnd(LocalTime.of(20, 0));
        config.setMaxCallAttempts(3);
        config.setRetryDelayMinutes(60);
        config.setPreferredChannel(channel);
        configurations.save(config);

        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Asha");
        customer.setLastName("Verma");
        customer.setPhone("+9184" + String.format("%08d", Math.floorMod(SEQ.incrementAndGet(), 100_000_000L)));
        customer.setEmail(email);
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customer.setCommunicationConsent(true);
        customers.save(customer);

        Policy policy = new Policy();
        policy.setOrganizationId(org.getId());
        policy.setCustomerId(customer.getId());
        policy.setAgentId(agent.getId());
        policy.setPolicyNumber("POL-2291");
        policy.setInsuranceProvider("Example Life");
        policy.setPolicyType("TERM");
        policy.setCurrencyCode("INR");
        policy.setPremiumAmount(new BigDecimal("4500.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setNextPremiumDueDate(LocalDate.of(2026, 9, 15));
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policies.save(policy);

        Reminder reminder = new Reminder();
        reminder.setOrganizationId(org.getId());
        reminder.setCustomerId(customer.getId());
        reminder.setPolicyId(policy.getId());
        reminder.setReminderType(Domain.ReminderType.PREMIUM_DUE);
        reminder.setChannel(channel);
        reminder.setStatus(Domain.ReminderStatus.PENDING);
        reminder.setScheduledAt(FixedClockConfiguration.FIXED_NOW.minusSeconds(60));
        reminder.setIdempotencyKey("msg:" + UUID.randomUUID());
        reminders.save(reminder);

        return new Scene(org, agent, customer, reminder);
    }

    private Reminder reloaded(Scene scene) {
        return reminders.findById(scene.reminder().getId()).orElseThrow();
    }

    private List<HumanTask> tasksFor(Scene scene) {
        return humanTasks.findByOrganizationIdAndStatusOrderByCreatedAtDesc(
                scene.org().getId(), Domain.HumanTaskStatus.OPEN, PageRequest.of(0, 10)).getContent();
    }

    @Test
    void anEmailReminderIsSentAndWrittenIntoTheCustomersHistory() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.EMAIL, "asha@example.test");
        stub(Domain.Channel.EMAIL).willReturn(SendResult.Outcome.ACCEPTED);

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.SENT);

        Reminder after = reloaded(scene);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.SENT);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getNextAttemptAt()).isNull();

        List<Conversation> logged = conversations.findAll().stream()
                .filter(c -> scene.customer().getId().equals(c.getCustomerId()))
                .toList();
        assertThat(logged).singleElement().satisfies(c -> {
            assertThat(c.getChannel()).isEqualTo(Domain.Channel.EMAIL);
            assertThat(c.getDirection()).isEqualTo(Domain.ConversationDirection.OUTBOUND);
            assertThat(c.getReminderId()).isEqualTo(scene.reminder().getId());
            assertThat(c.getAgentId())
                    .as("lands in the history of the agent who holds the customer")
                    .isEqualTo(scene.agent().getId());
        });

        List<ConversationMessage> lines =
                messages.findByConversationIdOrderByTimestampAsc(logged.get(0).getId());
        assertThat(lines).singleElement().satisfies(line ->
                assertThat(line.getMessage()).contains("Asha").contains("POL-2291"));
    }

    /** Written for the person receiving it, in rupees, with nothing internal in it. */
    @Test
    void theMessageSaysWhatTheCustomerNeedsAndNothingElse() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.EMAIL, "asha@example.test");
        dispatch.dispatch(scene.reminder().getId());

        OutboundMessage sent = stub(Domain.Channel.EMAIL).last();
        assertThat(sent.address()).isEqualTo("asha@example.test");
        assertThat(sent.subject()).contains("due").contains("POL-2291");
        assertThat(sent.body())
                .contains("Asha")
                .contains("INR 4500.00")
                .contains("15 September 2026");
        assertThat(sent.body())
                .as("no identifiers a customer has no use for")
                .doesNotContain(scene.customer().getId().toString())
                .doesNotContain(scene.reminder().getId().toString());
    }

    /**
     * The distinction worth drawing. A text arrives with a noise in the night; an
     * email waits to be opened.
     */
    @Test
    void aTextIsNotSentOutsideTheCallingWindowButAnEmailIs() {
        Scene texted = given("UTC", Domain.Channel.SMS, "asha@example.test"); // 06:00, opens at 09:00
        assertThat(dispatch.dispatch(texted.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.DEFERRED);
        assertThat(stub(Domain.Channel.SMS).sent()).isZero();
        assertThat(reloaded(texted).getStatus()).isEqualTo(Domain.ReminderStatus.PENDING);
        assertThat(reloaded(texted).getAttemptCount())
                .as("a message that was never sent is not an attempt")
                .isZero();

        Scene emailed = given("UTC", Domain.Channel.EMAIL, "asha@example.test");
        assertThat(dispatch.dispatch(emailed.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.SENT);
    }

    /** Being told to wait means being told when, not being looked at every hour. */
    @Test
    void aTextOutsideTheWindowWaitsUntilTheWindowOpens() {
        Scene scene = given("UTC", Domain.Channel.SMS, "asha@example.test");

        dispatch.dispatch(scene.reminder().getId());

        assertThat(reloaded(scene).getNextAttemptAt())
                .as("three hours from the frozen 06:00, when the window opens")
                .isEqualTo(FixedClockConfiguration.FIXED_NOW.plusSeconds(3 * 3600));
    }

    /**
     * Email is optional on a customer, so a tenant that switches to it can have
     * people it simply cannot reach. That is work for a person, not a failure to
     * repeat quietly for ever.
     */
    @Test
    void aCustomerWithNoEmailAddressIsNotEmailed() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.EMAIL, null);

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.CANCELLED);
        assertThat(stub(Domain.Channel.EMAIL).sent()).isZero();
        assertThat(reloaded(scene).getStatus()).isEqualTo(Domain.ReminderStatus.FAILED);
        assertThat(tasksFor(scene))
                .anySatisfy(task -> assertThat(task.getReason()).contains("NO_EMAIL_ADDRESS"));
    }

    @Test
    void anAddressTheProviderRejectsIsNotTriedAgain() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.EMAIL, "asha@example.test");
        stub(Domain.Channel.EMAIL).willReturn(SendResult.Outcome.INVALID_ADDRESS);

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.CANCELLED);

        Reminder after = reloaded(scene);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.FAILED);
        assertThat(after.getNextAttemptAt()).isNull();
        assertThat(after.getAttemptCount())
                .as("one attempt, not three, because more would be pointless")
                .isEqualTo(1);
        assertThat(tasksFor(scene))
                .anySatisfy(task -> assertThat(task.getReason()).contains("INVALID_ADDRESS"));
    }

    /** A provider refusing this message is not a reason to keep sending it. */
    @Test
    void aMessageTheProviderRefusesIsNotTriedAgain() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.EMAIL, "asha@example.test");
        stub(Domain.Channel.EMAIL).willReturn(SendResult.Outcome.REJECTED);

        dispatch.dispatch(scene.reminder().getId());

        assertThat(reloaded(scene).getStatus()).isEqualTo(Domain.ReminderStatus.FAILED);
        assertThat(stub(Domain.Channel.EMAIL).sent()).isEqualTo(1);
    }

    @Test
    void aProviderThatFailsIsTriedAgainLater() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.EMAIL, "asha@example.test");
        stub(Domain.Channel.EMAIL).willReturn(SendResult.Outcome.FAILED, SendResult.Outcome.ACCEPTED);

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.DEFERRED);

        Reminder after = reloaded(scene);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.PENDING);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getNextAttemptAt())
                .as("the tenant's retry delay of sixty minutes")
                .isEqualTo(FixedClockConfiguration.FIXED_NOW.plusSeconds(3600));

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.SENT);
    }

    /** A provider that always fails must run out rather than send for ever. */
    @Test
    void aProviderThatAlwaysFailsRunsOutOfAttempts() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.EMAIL, "asha@example.test");
        stub(Domain.Channel.EMAIL).willReturn(SendResult.Outcome.FAILED);

        for (int i = 0; i < 5; i++) {
            dispatch.dispatch(scene.reminder().getId());
        }

        Reminder after = reloaded(scene);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.FAILED);
        assertThat(after.getAttemptCount())
                .as("the tenant allows three")
                .isEqualTo(3);
        assertThat(stub(Domain.Channel.EMAIL).sent()).isEqualTo(3);
        assertThat(tasksFor(scene)).anySatisfy(task ->
                assertThat(task.getReason()).contains("FAILED_AFTER_3_ATTEMPTS"));
    }

    /** Consent is checked before a message as it is before a call. */
    @Test
    void anOptedOutCustomerIsNotMessaged() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.EMAIL, "asha@example.test");
        Customer customer = customers.findById(scene.customer().getId()).orElseThrow();
        customer.setOptedOut(true);
        customers.save(customer);

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.CANCELLED);
        assertThat(stub(Domain.Channel.EMAIL).sent()).isZero();
        assertThat(reloaded(scene).getStatus()).isEqualTo(Domain.ReminderStatus.CANCELLED);
    }

    /** WhatsApp is still in the enum and still carried by nothing. */
    @Test
    void aChannelWithNoProviderSendsNothingAndClaimsNothing() {
        Scene scene = given("Asia/Kolkata", Domain.Channel.WHATSAPP, "asha@example.test");

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.DEFERRED);
        assertThat(reloaded(scene).getStatus())
                .as("nothing was sent, so it must not claim to have been")
                .isEqualTo(Domain.ReminderStatus.PENDING);
        assertThat(reloaded(scene).getAttemptCount()).isZero();
    }
}
