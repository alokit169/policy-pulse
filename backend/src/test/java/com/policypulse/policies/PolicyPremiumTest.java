package com.policypulse.policies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.policypulse.AbstractIntegrationTest;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PolicyPremiumTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    /**
     * Reads JSON numbers exactly, so a money assertion tests the API rather than
     * this parser. Two defaults would otherwise get in the way: numbers are
     * parsed as doubles, turning 5000.00 into "5000.0"; and the node factory
     * strips trailing zeros, turning it into "5E+3".
     */
    private static final ObjectMapper PRECISE = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private AppUser agent;
    private String token;
    private String customerId;

    @BeforeEach
    void setUp() throws Exception {
        agent = createActiveAgent();
        token = tokenFor(agent);
        customerId = createCustomer();
    }

    private String createCustomer() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Asha");
        body.put("lastName", "Verma");
        body.put("phone", "+9196" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));

        String response = mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private Map<String, Object> policy(String start, String end, String amount, String frequency) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("insuranceProvider", "Example Life");
        body.put("policyType", "ENDOWMENT");
        body.put("premiumAmount", amount);
        body.put("premiumFrequency", frequency);
        body.put("policyStartDate", start);
        body.put("policyEndDate", end);
        return body;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        String response = mvc.perform(post("/api/policies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return PRECISE.readTree(response);
    }

    private JsonNode schedule(String policyId) throws Exception {
        String response = mvc.perform(get("/api/policies/" + policyId + "/premiums")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return PRECISE.readTree(response);
    }

    @Test
    void creatingAPolicyGeneratesItsSchedule() throws Exception {
        JsonNode created = create(policy("2024-01-01", "2027-01-01", "12000.00", "YEARLY"));

        assertThat(created.get("policyNumber").asText()).startsWith("P-");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("currencyCode").asText()).isEqualTo("INR");

        JsonNode instalments = schedule(created.get("id").asText());
        assertThat(instalments).hasSize(4);
        assertThat(instalments.get(0).get("dueDate").asText()).isEqualTo("2024-01-01");
        assertThat(instalments.get(3).get("dueDate").asText()).isEqualTo("2027-01-01");
    }

    /** A policy entered after the fact should not claim its past dues are upcoming. */
    @Test
    void backDatedInstalmentsAreCreatedOverdue() throws Exception {
        JsonNode created = create(policy("2020-01-01", "2021-01-01", "5000.00", "YEARLY"));

        JsonNode instalments = schedule(created.get("id").asText());
        assertThat(instalments).hasSize(2);
        assertThat(instalments.get(0).get("status").asText()).isEqualTo("OVERDUE");
    }

    @Test
    void theNextDueDateIsTheEarliestUnsettledInstalment() throws Exception {
        JsonNode created = create(policy("2024-01-01", "2027-01-01", "12000.00", "YEARLY"));
        assertThat(created.get("nextPremiumDueDate").asText()).isEqualTo("2024-01-01");
    }

    @Test
    void recordingAPaymentAdvancesThePolicyDates() throws Exception {
        String policyId = create(policy("2024-01-01", "2027-01-01", "12000.00", "YEARLY")).get("id").asText();
        String first = schedule(policyId).get(0).get("id").asText();

        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + first + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidDate\":\"2024-01-05\",\"paymentReference\":\"RCPT-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidDate").value("2024-01-05"))
                .andExpect(jsonPath("$.paymentReference").value("RCPT-1"));

        mvc.perform(get("/api/policies/" + policyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.lastPremiumPaidDate").value("2024-01-05"))
                .andExpect(jsonPath("$.nextPremiumDueDate").value("2025-01-01"));
    }

    @Test
    void aPremiumCannotBePaidTwice() throws Exception {
        String policyId = create(policy("2024-01-01", "2027-01-01", "12000.00", "YEARLY")).get("id").asText();
        String first = schedule(policyId).get(0).get("id").asText();
        String pay = "/api/policies/" + policyId + "/premiums/" + first + "/pay";

        mvc.perform(post(pay).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mvc.perform(post(pay).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aPaymentCannotBeDatedInTheFuture() throws Exception {
        String policyId = create(policy("2024-01-01", "2027-01-01", "12000.00", "YEARLY")).get("id").asText();
        String first = schedule(policyId).get(0).get("id").asText();

        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + first + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidDate\":\"2999-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void waivingSettlesAnInstalmentWithoutPayment() throws Exception {
        String policyId = create(policy("2024-01-01", "2027-01-01", "12000.00", "YEARLY")).get("id").asText();
        String first = schedule(policyId).get(0).get("id").asText();

        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + first + "/waive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAIVED"));

        mvc.perform(get("/api/policies/" + policyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.nextPremiumDueDate").value("2025-01-01"))
                // Waived is not paid: nothing was collected.
                .andExpect(jsonPath("$.lastPremiumPaidDate").doesNotExist());
    }

    @Test
    void aPaidPremiumCannotBeWaived() throws Exception {
        String policyId = create(policy("2024-01-01", "2027-01-01", "12000.00", "YEARLY")).get("id").asText();
        String first = schedule(policyId).get(0).get("id").asText();

        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + first + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + first + "/waive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /**
     * Money must survive the round trip exactly. Asserting on the text catches a
     * value that has been through a binary floating point type on the way.
     */
    @Test
    void moneyKeepsItsExactValueAndScale() throws Exception {
        JsonNode created = create(policy("2024-01-01", "2025-01-01", "12345.67", "YEARLY"));

        assertThat(created.get("premiumAmount").asText()).isEqualTo("12345.67");
        assertThat(new BigDecimal(created.get("premiumAmount").asText()))
                .isEqualByComparingTo(new BigDecimal("12345.67"));

        JsonNode instalments = schedule(created.get("id").asText());
        assertThat(instalments.get(0).get("amount").asText()).isEqualTo("12345.67");
    }

    @Test
    void impossibleAmountsAreRejected() throws Exception {
        for (String amount : new String[]{"0", "-1.00", "10.999"}) {
            mvc.perform(post("/api/policies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    policy("2024-01-01", "2025-01-01", amount, "YEARLY"))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void aTermThatEndsBeforeItStartsIsRejected() throws Exception {
        mvc.perform(post("/api/policies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                policy("2027-01-01", "2024-01-01", "12000.00", "YEARLY"))))
                .andExpect(status().isBadRequest());
    }

    /**
     * Correcting a policy's term rebuilds the schedule, but a premium that was
     * actually collected is a record of something that happened and must survive.
     */
    @Test
    void shorteningTheTermKeepsPaidInstalmentsAndDropsUnsettledOnes() throws Exception {
        String policyId = create(policy("2020-01-01", "2027-01-01", "5000.00", "YEARLY")).get("id").asText();
        assertThat(schedule(policyId)).hasSize(8);

        String first = schedule(policyId).get(0).get("id").asText();
        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + first + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidDate\":\"2020-01-03\"}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/policies/" + policyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                policy("2020-01-01", "2022-01-01", "5000.00", "YEARLY"))))
                .andExpect(status().isOk());

        JsonNode after = schedule(policyId);
        assertThat(after).hasSize(3);
        assertThat(after.get(0).get("status").asText()).isEqualTo("PAID");
        assertThat(after.get(0).get("paidDate").asText()).isEqualTo("2020-01-03");
    }

    /** Raising the premium must not rewrite what was already collected. */
    @Test
    void changingThePremiumLeavesSettledInstalmentsAtTheirOriginalAmount() throws Exception {
        String policyId = create(policy("2020-01-01", "2023-01-01", "5000.00", "YEARLY")).get("id").asText();
        String first = schedule(policyId).get(0).get("id").asText();

        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + first + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paidDate\":\"2020-01-02\"}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/policies/" + policyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                policy("2020-01-01", "2023-01-01", "7500.00", "YEARLY"))))
                .andExpect(status().isOk());

        JsonNode after = schedule(policyId);
        assertThat(after.get(0).get("amount").asText()).isEqualTo("5000.00");
        assertThat(after.get(1).get("amount").asText()).isEqualTo("7500.00");
    }

    /**
     * Regression: the currency default was applied on every save, so updating a
     * USD policy without repeating the field turned it into an INR one and left
     * the amounts untouched. The numbers stayed the same while their meaning
     * changed, which no later read could detect.
     */
    @Test
    void updatingAPolicyDoesNotResetItsCurrency() throws Exception {
        Map<String, Object> body = policy("2024-01-01", "2026-01-01", "1000.00", "YEARLY");
        body.put("currencyCode", "USD");
        String policyId = create(body).get("id").asText();

        Map<String, Object> update = policy("2024-01-01", "2026-01-01", "1000.00", "YEARLY");
        update.put("nomineeName", "Someone");

        mvc.perform(put("/api/policies/" + policyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("USD"));
    }

    @Test
    void aNewPolicyDefaultsToRupeesAndTheCurrencyCanStillBeChanged() throws Exception {
        String policyId = create(policy("2024-01-01", "2026-01-01", "1000.00", "YEARLY")).get("id").asText();

        Map<String, Object> update = policy("2024-01-01", "2026-01-01", "1000.00", "YEARLY");
        update.put("currencyCode", "GBP");

        mvc.perform(put("/api/policies/" + policyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("GBP"));
    }

    /**
     * PUT replaces the whole policy, so omitting the term clears it and the
     * generated schedule goes with it. Pinned deliberately: unsettled instalments
     * are derived and come back when the dates do, but anything settled must
     * survive regardless.
     */
    @Test
    void clearingTheTermRemovesUnsettledInstalmentsButKeepsSettledOnes() throws Exception {
        String policyId = create(policy("2024-01-01", "2027-01-01", "1000.00", "YEARLY")).get("id").asText();
        assertThat(schedule(policyId)).hasSize(4);

        String first = schedule(policyId).get(0).get("id").asText();
        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + first + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paidDate\":\"2024-01-02\"}"))
                .andExpect(status().isOk());

        Map<String, Object> withoutDates = new HashMap<>();
        withoutDates.put("customerId", customerId);
        withoutDates.put("insuranceProvider", "Example Life");
        withoutDates.put("policyType", "ENDOWMENT");
        withoutDates.put("premiumAmount", "1000.00");
        withoutDates.put("premiumFrequency", "YEARLY");

        mvc.perform(put("/api/policies/" + policyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withoutDates)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextPremiumDueDate").doesNotExist());

        JsonNode after = schedule(policyId);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).get("status").asText()).isEqualTo("PAID");
    }
}
