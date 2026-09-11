package com.policypulse.ai;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.FixedClockConfiguration;
import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.followups.FollowUpRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.reminders.Reminder;
import com.policypulse.reminders.ReminderRepository;
import com.policypulse.tasks.HumanTask;
import com.policypulse.tasks.HumanTaskRepository;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the assistant is allowed to cause.
 *
 * <p>The rule these exist to hold: a customer saying they have paid is a claim,
 * and no claim may ever record money as received.
 */
class AiSafetyTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    @Autowired private StubAIProvider ai;
    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private PremiumPaymentRepository premiums;
    @Autowired private FollowUpRepository followUps;
    @Autowired private HumanTaskRepository humanTasks;
    @Autowired private ReminderRepository reminders;

    private record Scene(AppUser agent, String token, Customer customer, Policy policy,
                         PremiumPayment instalment, String conversationId) {
    }

    private LocalDate today(String timezone) {
        return LocalDate.ofInstant(FixedClockConfiguration.FIXED_NOW, ZoneId.of(timezone));
    }

    /** An agent, a customer with one unpaid instalment, and a call with one line. */
    private Scene given() throws Exception {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone("UTC");
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        String token = tokenFor(agent);

        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Asha");
        customer.setLastName("Verma");
        customer.setPhone("+9188" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));
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

        PremiumPayment instalment = new PremiumPayment();
        instalment.setOrganizationId(org.getId());
        instalment.setPolicyId(policy.getId());
        instalment.setAmount(new BigDecimal("1000.00"));
        instalment.setDueDate(today("UTC").minusDays(5));
        instalment.setStatus(Domain.PremiumStatus.OVERDUE);
        premiums.save(instalment);

        Map<String, Object> conversationBody = new HashMap<>();
        conversationBody.put("customerId", customer.getId().toString());
        conversationBody.put("policyId", policy.getId().toString());
        conversationBody.put("channel", "VOICE");
        conversationBody.put("direction", "OUTBOUND");

        String created = mvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conversationBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversationId = objectMapper.readTree(created).get("id").asText();

        mvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("sender", "CUSTOMER", "message", "Noted."))))
                .andExpect(status().isCreated());

        return new Scene(agent, token, customer, policy, instalment, conversationId);
    }

    private String analyse(Scene scene) throws Exception {
        return mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private PremiumPayment reloaded(Scene scene) {
        return premiums.findById(scene.instalment().getId()).orElseThrow();
    }

    /**
     * The rule the whole validation layer exists for. However sure the model is,
     * a claim of payment leaves the instalment exactly as it was.
     */
    @Test
    void aClaimedPaymentIsNeverRecordedAsReceived() throws Exception {
        Scene scene = given();
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.PAYMENT_CONFIRMED, 1.0, null,
                new BigDecimal("1000.00"), "Customer says it is paid.", "POSITIVE"));

        mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acted").value(true))
                .andExpect(jsonPath("$.actions", org.hamcrest.Matchers.hasItem("PREMIUM_FLAGGED_FOR_VERIFICATION")))
                .andExpect(jsonPath("$.actions", org.hamcrest.Matchers.hasItem("HUMAN_TASK_RAISED")));

        PremiumPayment after = reloaded(scene);
        assertThat(after.getStatus())
                .as("a claim must never mark money as received")
                .isEqualTo(Domain.PremiumStatus.OVERDUE);
        assertThat(after.getPaidDate()).isNull();
        assertThat(after.isVerificationPending()).isTrue();

        assertThat(humanTasks.findByConversationId(UUID.fromString(scene.conversationId())))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.getReason()).isEqualTo("VERIFY_CLAIMED_PAYMENT");
                    assertThat(task.getPriority()).isEqualTo(Domain.HumanTaskPriority.HIGH);
                });
    }

    /** A person closes the loop, either by recording the payment or dismissing it. */
    @Test
    void dismissingAClaimLeavesTheInstalmentUnpaid() throws Exception {
        Scene scene = given();
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.PAYMENT_CONFIRMED, 1.0, null, null, "Paid", "POSITIVE"));
        analyse(scene);
        assertThat(reloaded(scene).isVerificationPending()).isTrue();

        mvc.perform(post("/api/policies/" + scene.policy().getId()
                        + "/premiums/" + scene.instalment().getId() + "/dismiss-claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationPending").value(false))
                .andExpect(jsonPath("$.status").value("OVERDUE"));

        assertThat(reloaded(scene).getPaidDate()).isNull();
    }

    /** A weak reading buys a person's attention, never an action. */
    @Test
    void aLowConfidenceReadingChangesNothing() throws Exception {
        Scene scene = given();
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.PAYMENT_COMMITMENT, 0.4,
                today("UTC").plusDays(3), null, "Might have promised something.", "NEUTRAL"));

        mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(jsonPath("$.acted").value(false));

        assertThat(followUps.findByCustomerIdOrderByDueAtAsc(scene.customer().getId()))
                .as("nothing may be created from a reading this weak")
                .isEmpty();
        assertThat(humanTasks.findByConversationId(UUID.fromString(scene.conversationId())))
                .singleElement()
                .extracting(HumanTask::getReason)
                .isEqualTo("LOW_CONFIDENCE_PAYMENT_COMMITMENT");
    }

    @Test
    void aConfidentPromiseWithADateBecomesAFollowUp() throws Exception {
        Scene scene = given();
        LocalDate promised = today("UTC").plusDays(3);
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.PAYMENT_COMMITMENT, 0.9, promised,
                null, "Will pay Friday.", "POSITIVE"));

        mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(jsonPath("$.acted").value(true))
                .andExpect(jsonPath("$.actions", org.hamcrest.Matchers.hasItem("FOLLOW_UP_CREATED")));

        assertThat(followUps.findByCustomerIdOrderByDueAtAsc(scene.customer().getId()))
                .singleElement()
                .satisfies(followUp -> {
                    assertThat(followUp.getCommitmentDate()).isEqualTo(promised);
                    assertThat(followUp.getReason()).isEqualTo("PAYMENT_COMMITMENT");
                    // Owned by the customer's agent, not by whatever took the call.
                    assertThat(followUp.getAssignedAgentId()).isEqualTo(scene.agent().getId());
                });

        // The instalment is untouched: a promise is not a payment either.
        assertThat(reloaded(scene).getStatus()).isEqualTo(Domain.PremiumStatus.OVERDUE);
    }

    @Test
    void aPromiseWithNoUsableDateGoesToAPerson() throws Exception {
        Scene scene = given();
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.PAYMENT_COMMITMENT, 0.95, null,
                null, "Said they would pay sometime.", "NEUTRAL"));

        mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(jsonPath("$.acted").value(false));

        assertThat(followUps.findByCustomerIdOrderByDueAtAsc(scene.customer().getId())).isEmpty();
        assertThat(humanTasks.findByConversationId(UUID.fromString(scene.conversationId())))
                .singleElement()
                .extracting(HumanTask::getReason)
                .isEqualTo("COMMITMENT_WITHOUT_A_USABLE_DATE");
    }

    @Test
    void aDateAlreadyPastIsNotTreatedAsAPromise() throws Exception {
        Scene scene = given();
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.PAYMENT_COMMITMENT, 0.95,
                today("UTC").minusDays(2), null, "Said they would pay last week.", "NEUTRAL"));

        mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(jsonPath("$.acted").value(false));

        assertThat(followUps.findByCustomerIdOrderByDueAtAsc(scene.customer().getId())).isEmpty();
    }

    /** Stopping contact is safe to get wrong in the direction it fails. */
    @Test
    void aClearOptOutWithdrawsConsentAndDropsQueuedReminders() throws Exception {
        Scene scene = given();

        Reminder queued = new Reminder();
        queued.setOrganizationId(scene.customer().getOrganizationId());
        queued.setCustomerId(scene.customer().getId());
        queued.setPolicyId(scene.policy().getId());
        queued.setReminderType(Domain.ReminderType.PREMIUM_OVERDUE);
        queued.setChannel(Domain.Channel.IN_APP);
        queued.setStatus(Domain.ReminderStatus.PENDING);
        queued.setScheduledAt(FixedClockConfiguration.FIXED_NOW);
        queued.setIdempotencyKey("test:" + UUID.randomUUID());
        reminders.save(queued);

        ai.willReturn(new IntentAnalysis(Domain.AiIntent.OPT_OUT, 0.97, null, null,
                "Asked not to be called.", "NEGATIVE"));

        mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(jsonPath("$.acted").value(true))
                .andExpect(jsonPath("$.actions", org.hamcrest.Matchers.hasItem("CUSTOMER_OPTED_OUT")));

        Customer after = customers.findById(scene.customer().getId()).orElseThrow();
        assertThat(after.isOptedOut()).isTrue();
        assertThat(after.isCommunicationConsent()).isFalse();

        assertThat(reminders.findById(queued.getId()).orElseThrow().getStatus())
                .as("queued reminders must not still go out after an opt-out")
                .isEqualTo(Domain.ReminderStatus.CANCELLED);
    }

    /** Changing a customer's own record needs more than the ordinary threshold. */
    @Test
    void anUncertainOptOutIsNotApplied() throws Exception {
        Scene scene = given();
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.OPT_OUT, 0.75, null, null,
                "Might have asked not to be called.", "NEGATIVE"));

        mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(jsonPath("$.acted").value(false));

        assertThat(customers.findById(scene.customer().getId()).orElseThrow().isOptedOut()).isFalse();
        assertThat(humanTasks.findByConversationId(UUID.fromString(scene.conversationId())))
                .singleElement()
                .extracting(HumanTask::getReason)
                .isEqualTo("POSSIBLE_OPT_OUT");
    }

    @Test
    void askingForAPersonRaisesUrgentWork() throws Exception {
        Scene scene = given();
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.REQUEST_HUMAN_AGENT, 0.95, null, null,
                "Asked for a person.", "NEUTRAL"));

        analyse(scene);

        assertThat(humanTasks.findByConversationId(UUID.fromString(scene.conversationId())))
                .singleElement()
                .extracting(HumanTask::getPriority)
                .isEqualTo(Domain.HumanTaskPriority.URGENT);
    }

    @Test
    void thereMustBeSomethingToRead() throws Exception {
        Organization org = createOrganization();
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        String token = tokenFor(agent);

        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Quiet");
        customer.setLastName("Line");
        customer.setPhone("+9187" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customers.save(customer);

        String created = mvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customer.getId().toString(),
                                "channel", "VOICE",
                                "direction", "OUTBOUND"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/conversations/" + objectMapper.readTree(created).get("id").asText() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** Analysis must not become a way to reach a call the caller cannot open. */
    @Test
    void anotherTenantCannotAnalyseAConversation() throws Exception {
        Scene scene = given();
        String intruder = tokenFor(createActiveAgent());

        ai.willReturn(new IntentAnalysis(Domain.AiIntent.PAYMENT_CONFIRMED, 1.0, null, null, "Paid", "POSITIVE"));

        mvc.perform(post("/api/conversations/" + scene.conversationId() + "/analyse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        assertThat(reloaded(scene).isVerificationPending()).isFalse();
    }

    @Test
    void analysisRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/conversations/" + UUID.randomUUID() + "/analyse"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void raisedWorkAppearsInTheTaskQueue() throws Exception {
        Scene scene = given();
        ai.willReturn(new IntentAnalysis(Domain.AiIntent.CANNOT_PAY, 0.9, null, null,
                "Customer cannot pay.", "NEGATIVE"));
        analyse(scene);

        List<HumanTask> raised = humanTasks.findByConversationId(UUID.fromString(scene.conversationId()));
        assertThat(raised).hasSize(1);

        mvc.perform(post("/api/tasks/" + raised.get(0).getId() + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(post("/api/tasks/" + raised.get(0).getId() + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scene.token()))
                .andExpect(status().isConflict());
    }
}
