package com.policypulse.followups;

import com.fasterxml.jackson.databind.JsonNode;
import com.policypulse.AbstractIntegrationTest;
import com.policypulse.FixedClockConfiguration;
import com.policypulse.common.Domain;
import com.policypulse.organizations.Organization;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FollowUpTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    private Organization tenantOn(String timezone) {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone(timezone);
        org.setStatus(Domain.EntityStatus.ACTIVE);
        return organizations.save(org);
    }

    private LocalDate today(String timezone) {
        return LocalDate.ofInstant(FixedClockConfiguration.FIXED_NOW, ZoneId.of(timezone));
    }

    private String customerFor(String token) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Asha");
        body.put("lastName", "Verma");
        body.put("phone", "+9189" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));

        String response = mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private Map<String, Object> commitment(String customerId, LocalDate date) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("reason", "PAYMENT_COMMITMENT");
        if (date != null) body.put("commitmentDate", date.toString());
        return body;
    }

    private JsonNode create(String token, Map<String, Object> body) throws Exception {
        String response = mvc.perform(post("/api/follow-ups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void followUpRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/follow-ups")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/follow-ups").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /** A promise to pay with no date is not a promise: nothing to come back on. */
    @Test
    void aPaymentCommitmentNeedsTheDateTheCustomerGave() throws Exception {
        String token = tokenFor(createActiveAgent());

        mvc.perform(post("/api/follow-ups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commitment(customerFor(token), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCommitmentDateInThePastIsRejected() throws Exception {
        String token = tokenFor(createActiveAgent());

        mvc.perform(post("/api/follow-ups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                commitment(customerFor(token), today("UTC").minusDays(1)))))
                .andExpect(status().isBadRequest());
    }

    /**
     * A commitment comes due at the start of the customer's working day, in their
     * own timezone, so nothing is ever waiting for an agent at midnight.
     */
    @Test
    void aCommitmentComesDueAtTheStartOfTheCustomersDay() throws Exception {
        String timezone = "Asia/Kolkata";
        Organization org = tenantOn(timezone);
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        String token = tokenFor(agent);

        LocalDate commitmentDate = today(timezone).plusDays(3);
        JsonNode created = create(token, commitment(customerFor(token), commitmentDate));

        Instant expected = ZonedDateTime
                .of(commitmentDate, LocalTime.of(9, 0), ZoneId.of(timezone))
                .toInstant();

        assertThat(Instant.parse(created.get("dueAt").asText())).isEqualTo(expected);
        assertThat(created.get("status").asText()).isEqualTo("OPEN");
        assertThat(created.get("commitmentDate").asText()).isEqualTo(commitmentDate.toString());
    }

    /**
     * The work follows the customer, not whoever happened to log the call, so a
     * manager recording a promise does not end up owning it.
     */
    @Test
    void aFollowUpIsOwnedByTheCustomersAgent() throws Exception {
        Organization org = createOrganization();
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);

        String agentToken = tokenFor(agent);
        String customerId = customerFor(agentToken);

        JsonNode created = create(tokenFor(manager), commitment(customerId, today("UTC").plusDays(2)));

        assertThat(created.get("assignedAgentId").asText()).isEqualTo(agent.getId().toString());
    }

    @Test
    void aFollowUpCanBeCompletedOnlyOnce() throws Exception {
        String token = tokenFor(createActiveAgent());
        String id = create(token, commitment(customerFor(token), today("UTC").plusDays(1))).get("id").asText();

        mvc.perform(post("/api/follow-ups/" + id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(post("/api/follow-ups/" + id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/follow-ups/" + id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void aFollowUpCanBeCancelledWithANote() throws Exception {
        String token = tokenFor(createActiveAgent());
        String id = create(token, commitment(customerFor(token), today("UTC").plusDays(1))).get("id").asText();

        mvc.perform(post("/api/follow-ups/" + id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Paid in branch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.notes").value("Paid in branch"));
    }

    @Test
    void aFollowUpIsInvisibleToAnotherTenant() throws Exception {
        String owner = tokenFor(createActiveAgent());
        String intruder = tokenFor(createActiveAgent());
        String id = create(owner, commitment(customerFor(owner), today("UTC").plusDays(1))).get("id").asText();

        mvc.perform(get("/api/follow-ups/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/follow-ups/" + id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/follow-ups").header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anAgentSeesOnlyTheirOwnFollowUps() throws Exception {
        Organization org = createOrganization();
        AppUser mine = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser peer = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        String peerToken = tokenFor(peer);
        create(peerToken, commitment(customerFor(peerToken), today("UTC").plusDays(1)));

        mvc.perform(get("/api/follow-ups").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(mine)))
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/follow-ups").header(HttpHeaders.AUTHORIZATION, "Bearer " + peerToken))
                .andExpect(jsonPath("$.total").value(1));
    }

    /** Linking to someone else's conversation would attach the wrong history. */
    @Test
    void aFollowUpCannotCiteAConversationWithADifferentCustomer() throws Exception {
        String token = tokenFor(createActiveAgent());
        String customerA = customerFor(token);
        String customerB = customerFor(token);

        Map<String, Object> conversationBody = new HashMap<>();
        conversationBody.put("customerId", customerB);
        conversationBody.put("channel", "VOICE");
        conversationBody.put("direction", "OUTBOUND");

        String conversation = mvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conversationBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> body = commitment(customerA, today("UTC").plusDays(1));
        body.put("conversationId", objectMapper.readTree(conversation).get("id").asText());

        mvc.perform(post("/api/follow-ups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCommitmentMadeDuringACallIsLinkedToIt() throws Exception {
        String token = tokenFor(createActiveAgent());
        String customerId = customerFor(token);

        Map<String, Object> conversationBody = new HashMap<>();
        conversationBody.put("customerId", customerId);
        conversationBody.put("channel", "VOICE");
        conversationBody.put("direction", "OUTBOUND");

        String conversation = mvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conversationBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversationId = objectMapper.readTree(conversation).get("id").asText();

        Map<String, Object> body = commitment(customerId, today("UTC").plusDays(2));
        body.put("conversationId", conversationId);

        assertThat(create(token, body).get("conversationId").asText()).isEqualTo(conversationId);

        mvc.perform(get("/api/customers/" + customerId + "/follow-ups")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
