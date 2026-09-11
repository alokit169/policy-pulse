package com.policypulse.voice;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.FixedClockConfiguration;
import com.policypulse.common.Domain;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Placing calls, and the rules that decide whether one may be placed at all.
 *
 * <p>The clock is frozen at 06:00 UTC. A tenant on UTC whose window opens at
 * 09:00 is therefore shut, while one in Asia/Kolkata is at 11:30 and open. That
 * pair makes the window testable without waiting for a particular hour.
 */
class VoiceCallTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    @Autowired private StubVoiceProvider voice;
    @Autowired private ReminderDispatchService dispatch;
    @Autowired private ReminderRepository reminders;
    @Autowired private ReminderConfigurationRepository configurations;
    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private ConversationRepository conversations;
    @Autowired private ConversationMessageRepository messages;
    @Autowired private HumanTaskRepository humanTasks;

    private record Scene(Organization org, AppUser agent, Customer customer, Reminder reminder) {
    }

    /** A tenant, a customer who consents, and one voice reminder already due. */
    private Scene given(String timezone, int maxAttempts, int retryMinutes) {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone(timezone);
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        ReminderConfiguration config = new ReminderConfiguration();
        config.setOrganizationId(org.getId());
        config.setAllowedCallingStart(LocalTime.of(9, 0));
        config.setAllowedCallingEnd(LocalTime.of(20, 0));
        config.setMaxCallAttempts(maxAttempts);
        config.setRetryDelayMinutes(retryMinutes);
        config.setPreferredChannel(Domain.Channel.VOICE);
        configurations.save(config);

        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Asha");
        customer.setLastName("Verma");
        customer.setPhone("+9186" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));
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
        policy.setPremiumAmount(new BigDecimal("1000.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policies.save(policy);

        Reminder reminder = new Reminder();
        reminder.setOrganizationId(org.getId());
        reminder.setCustomerId(customer.getId());
        reminder.setPolicyId(policy.getId());
        reminder.setReminderType(Domain.ReminderType.PREMIUM_OVERDUE);
        reminder.setChannel(Domain.Channel.VOICE);
        reminder.setStatus(Domain.ReminderStatus.PENDING);
        reminder.setScheduledAt(FixedClockConfiguration.FIXED_NOW.minusSeconds(60));
        reminder.setIdempotencyKey("voice:" + UUID.randomUUID());
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

    /**
     * The rule that matters most here. A reminder can be picked up hours after it
     * was queued, so the window is checked against the clock at the moment of
     * dialling, not when the work was created.
     */
    @Test
    void nobodyIsCalledOutsideTheTenantsCallingWindow() {
        Scene scene = given("UTC", 3, 180); // 06:00 UTC, window opens at 09:00
        voice.willReturn(StubVoiceProvider.answered());

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.DEFERRED);

        assertThat(voice.callsPlaced()).as("nothing may be dialled before the window opens").isZero();

        Reminder after = reloaded(scene);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.PENDING);
        assertThat(after.getAttemptCount()).as("a call that never happened is not an attempt").isZero();
    }

    /**
     * A reminder parked for a shut window was told when to come back based on
     * the window as it then was. Widening the window has to reconsider it, or a
     * manager opening the afternoon to catch up on today's calls sees nothing
     * happen until tomorrow.
     */
    @Test
    void wideningTheWindowWakesTheRemindersItHadShutOut() throws Exception {
        Scene scene = given("UTC", 3, 180); // 06:00 UTC, window opens at 09:00
        voice.willReturn(StubVoiceProvider.answered());

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.DEFERRED);
        assertThat(reloaded(scene).getNextAttemptAt()).isNotNull();

        AppUser manager = createUserIn(scene.org().getId(),
                Domain.Role.ORGANIZATION_ADMIN, Domain.EntityStatus.ACTIVE);
        mvc.perform(put("/api/reminders/configuration")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"daysBeforeDue\":\"0\",\"daysAfterDue\":\"2\","
                                + "\"maxCallAttempts\":3,\"retryDelayMinutes\":180,"
                                + "\"allowedCallingStart\":\"00:00:00\",\"allowedCallingEnd\":\"23:59:00\","
                                + "\"preferredChannel\":\"VOICE\"}"))
                .andExpect(status().isOk());

        assertThat(reloaded(scene).getNextAttemptAt())
                .as("no longer waiting for a window that has moved")
                .isNull();

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.SENT);
        assertThat(voice.callsPlaced()).isEqualTo(1);
    }

    /**
     * Being told to wait is not the same as being looked at again every hour. A
     * reminder that keeps its original moment stays at the head of the due queue
     * all night, ahead of work that could actually go out.
     */
    @Test
    void aReminderOutsideTheWindowWaitsUntilTheWindowOpens() {
        Scene scene = given("UTC", 3, 180); // 06:00 UTC, window opens at 09:00
        voice.willReturn(StubVoiceProvider.answered());

        dispatch.dispatch(scene.reminder().getId());

        assertThat(reloaded(scene).getNextAttemptAt())
                .as("three hours from the frozen 06:00, when the window opens")
                .isEqualTo(FixedClockConfiguration.FIXED_NOW.plusSeconds(3 * 3600));
    }

    @Test
    void aCallIsPlacedOnceTheWindowIsOpen() {
        Scene scene = given("Asia/Kolkata", 3, 180); // 11:30 local, open
        voice.willReturn(StubVoiceProvider.answered());

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.SENT);

        assertThat(voice.callsPlaced()).isEqualTo(1);
        Reminder after = reloaded(scene);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.COMPLETED);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getLastAttemptAt()).isEqualTo(FixedClockConfiguration.FIXED_NOW);
    }

    @Test
    void anansweredCallIsRecordedAsAConversationWithItsTranscript() {
        Scene scene = given("Asia/Kolkata", 3, 180);
        voice.willReturn(StubVoiceProvider.answered());

        dispatch.dispatch(scene.reminder().getId());

        var held = conversations.findByCustomerIdOrderByStartedAtDesc(scene.customer().getId());
        assertThat(held).hasSize(1);
        assertThat(held.get(0).getChannel()).isEqualTo(Domain.Channel.VOICE);
        assertThat(held.get(0).getReminderId()).isEqualTo(scene.reminder().getId());
        assertThat(held.get(0).getDurationSeconds()).isEqualTo(30);
        // The customer's own agent, so it lands in their history although nobody
        // was on the line.
        assertThat(held.get(0).getAgentId()).isEqualTo(scene.agent().getId());

        var transcript = messages.findByConversationIdOrderByTimestampAsc(held.get(0).getId());
        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(0).getSender()).isEqualTo("ASSISTANT");
        assertThat(transcript.get(1).getSender()).isEqualTo("CUSTOMER");
        assertThat(transcript.get(1).getMessage()).contains("pay on Friday");
    }

    @Test
    void aCallThatRingsOutIsTriedAgainAfterTheConfiguredDelay() {
        Scene scene = given("Asia/Kolkata", 3, 90);
        voice.willReturn(StubVoiceProvider.outcome(CallResult.Outcome.NO_ANSWER));

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.DEFERRED);

        Reminder after = reloaded(scene);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.PENDING);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getNextAttemptAt())
                .isEqualTo(FixedClockConfiguration.FIXED_NOW.plusSeconds(90 * 60));

        // Not due again until that moment arrives.
        assertThat(dispatch.dueReminderIdsFor(scene.org().getId()))
                .as("a reminder waiting on its retry delay is not due yet")
                .doesNotContain(scene.reminder().getId());
    }

    @Test
    void callingStopsAtTheTenantsAttemptLimitAndTellsSomeone() {
        Scene scene = given("Asia/Kolkata", 2, 90);
        voice.willReturn(StubVoiceProvider.outcome(CallResult.Outcome.NO_ANSWER));

        dispatch.dispatch(scene.reminder().getId());

        // Second attempt: clear the wait so it is due again.
        Reminder waiting = reloaded(scene);
        waiting.setNextAttemptAt(FixedClockConfiguration.FIXED_NOW.minusSeconds(1));
        reminders.save(waiting);

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.CANCELLED);

        Reminder after = reloaded(scene);
        assertThat(after.getAttemptCount()).isEqualTo(2);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.FAILED);
        assertThat(after.getNextAttemptAt()).as("no further attempt may be queued").isNull();

        assertThat(tasksFor(scene))
                .as("giving up quietly would leave the customer unreached and nobody aware")
                .anySatisfy(task -> assertThat(task.getReason()).startsWith("COULD_NOT_REACH_"));
    }

    /** Dialling it again cannot help, so it is not retried. */
    @Test
    void anUnusableNumberIsNotTriedAgain() {
        Scene scene = given("Asia/Kolkata", 5, 90);
        voice.willReturn(StubVoiceProvider.outcome(CallResult.Outcome.INVALID_NUMBER));

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.CANCELLED);

        Reminder after = reloaded(scene);
        assertThat(after.getStatus()).isEqualTo(Domain.ReminderStatus.FAILED);
        assertThat(after.getNextAttemptAt()).isNull();
        assertThat(after.getAttemptCount())
                .as("one attempt, not five, because more would be pointless")
                .isEqualTo(1);

        assertThat(tasksFor(scene))
                .anySatisfy(task -> assertThat(task.getReason()).contains("INVALID_PHONE_NUMBER"));
    }

    /**
     * The column is NOT NULL, so a customer always has a phone field; it can
     * still be empty, from an import or a correction. Dialling an empty string
     * would burn an attempt on nothing.
     */
    @Test
    void aCustomerWithNoUsableNumberIsNotDialled() {
        Scene scene = given("Asia/Kolkata", 3, 90);
        Customer customer = customers.findById(scene.customer().getId()).orElseThrow();
        customer.setPhone("");
        customers.save(customer);

        voice.willReturn(StubVoiceProvider.answered());

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.CANCELLED);
        assertThat(voice.callsPlaced()).isZero();
        assertThat(tasksFor(scene)).anySatisfy(t -> assertThat(t.getReason()).contains("NO_PHONE_NUMBER"));
    }

    /** Consent is checked before the window, so an opt-out is never dialled. */
    @Test
    void aCustomerWhoHasOptedOutIsNeverCalled() {
        Scene scene = given("Asia/Kolkata", 3, 90);
        Customer customer = customers.findById(scene.customer().getId()).orElseThrow();
        customer.setOptedOut(true);
        customers.save(customer);

        voice.willReturn(StubVoiceProvider.answered());

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.CANCELLED);
        assertThat(voice.callsPlaced()).isZero();
        assertThat(reloaded(scene).getStatus()).isEqualTo(Domain.ReminderStatus.CANCELLED);
    }

    /** The window is the tenant's, so the same instant differs between them. */
    @Test
    void theWindowIsReadInTheTenantsOwnTimezone() {
        Scene shut = given("UTC", 3, 90);
        Scene open = given("Asia/Kolkata", 3, 90);
        voice.willReturn(StubVoiceProvider.answered());

        assertThat(dispatch.dispatch(shut.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.DEFERRED);
        assertThat(dispatch.dispatch(open.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.SENT);

        assertThat(reloaded(shut).getAttemptCount()).isZero();
        assertThat(reloaded(open).getAttemptCount()).isEqualTo(1);
    }

    @Test
    void theCallServiceRunsWithoutASignedInUser() {
        Scene scene = given("Asia/Kolkata", 3, 90);
        voice.willReturn(StubVoiceProvider.answered());

        // No security context is set here on purpose: the scheduler has none, and
        // anything reaching for one would throw rather than place the call.
        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.SENT);
    }

    @Test
    void aRetryBecomesDueOnceItsMomentPasses() {
        Scene scene = given("Asia/Kolkata", 3, 30);
        voice.willReturn(StubVoiceProvider.outcome(CallResult.Outcome.BUSY));
        dispatch.dispatch(scene.reminder().getId());

        Reminder waiting = reloaded(scene);
        assertThat(dispatch.dueReminderIdsFor(scene.org().getId())).doesNotContain(waiting.getId());

        waiting.setNextAttemptAt(Instant.ofEpochSecond(0));
        reminders.save(waiting);

        assertThat(dispatch.dueReminderIdsFor(scene.org().getId()))
                .as("once the wait is over it should be picked up again")
                .contains(waiting.getId());
    }

    @Test
    void theTenantsOwnDayDecidesTheWindowNotTheServers() {
        // Kolkata is 11:30 at the frozen instant; a window of 12:00 to 20:00 is
        // therefore still shut there even though the server clock reads 06:00.
        Scene scene = given("Asia/Kolkata", 3, 90);
        ReminderConfiguration config = configurations
                .findByOrganizationId(scene.org().getId()).orElseThrow();
        config.setAllowedCallingStart(LocalTime.of(12, 0));
        configurations.save(config);

        voice.willReturn(StubVoiceProvider.answered());

        assertThat(dispatch.dispatch(scene.reminder().getId()))
                .isEqualTo(ReminderDispatchService.Outcome.DEFERRED);
        assertThat(voice.callsPlaced()).isZero();

        assertThat(Instant.now(java.time.Clock.fixed(FixedClockConfiguration.FIXED_NOW, ZoneId.of("UTC")))
                .atZone(ZoneId.of("Asia/Kolkata")).toLocalTime())
                .isEqualTo(LocalTime.of(11, 30));
    }
}
