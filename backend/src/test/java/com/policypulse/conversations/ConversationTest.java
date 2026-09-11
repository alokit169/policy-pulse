package com.policypulse.conversations;

import com.fasterxml.jackson.databind.JsonNode;
import com.policypulse.AbstractIntegrationTest;
import com.policypulse.common.Domain;
import com.policypulse.organizations.Organization;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    private String customerFor(String token) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Asha");
        body.put("lastName", "Verma");
        body.put("phone", "+9190" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));

        String response = mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private Map<String, Object> conversationBody(String customerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("channel", "VOICE");
        body.put("direction", "OUTBOUND");
        return body;
    }

    private JsonNode start(String token, String customerId) throws Exception {
        String response = mvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conversationBody(customerId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void addMessage(String token, String conversationId, String sender, String text) throws Exception {
        Map<String, Object> body = Map.of("sender", sender, "message", text);
        mvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void conversationRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/conversations/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void aConversationIsLoggedWithATranscriptAndThenClosed() throws Exception {
        String token = tokenFor(createActiveAgent());
        String conversationId = start(token, customerFor(token)).get("id").asText();

        addMessage(token, conversationId, "AGENT", "Calling about the premium due on Friday.");
        addMessage(token, conversationId, "CUSTOMER", "I will pay it on Friday.");

        mvc.perform(get("/api/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].sender").value("AGENT"))
                .andExpect(jsonPath("$.messages[1].sender").value("CUSTOMER"));

        mvc.perform(post("/api/conversations/" + conversationId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"PAYMENT_COMMITMENT\",\"sentiment\":\"POSITIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.outcome").value("PAYMENT_COMMITMENT"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                // Measured from the stored start, so it cannot disagree with the
                // timestamps either side of it.
                .andExpect(jsonPath("$.durationSeconds").value(0));
    }

    @Test
    void aClosedConversationAcceptsNoMoreLinesAndCannotBeClosedTwice() throws Exception {
        String token = tokenFor(createActiveAgent());
        String conversationId = start(token, customerFor(token)).get("id").asText();

        mvc.perform(post("/api/conversations/" + conversationId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sender", "AGENT", "message", "One more"))))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/conversations/" + conversationId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    /** A listing of twenty conversations must not drag every transcript with it. */
    @Test
    void listingsOmitTheTranscript() throws Exception {
        String token = tokenFor(createActiveAgent());
        String conversationId = start(token, customerFor(token)).get("id").asText();
        addMessage(token, conversationId, "AGENT", "Hello");

        mvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].messages").doesNotExist());
    }

    @Test
    void aConversationIsInvisibleToAnotherTenant() throws Exception {
        String owner = tokenFor(createActiveAgent());
        String intruder = tokenFor(createActiveAgent());
        String conversationId = start(owner, customerFor(owner)).get("id").asText();

        mvc.perform(get("/api/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sender", "AGENT", "message", "Listening in"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aConversationCannotBeLoggedAgainstAnotherTenantsCustomer() throws Exception {
        String owner = tokenFor(createActiveAgent());
        String outsider = tokenFor(createActiveAgent());
        String theirCustomer = customerFor(outsider);

        mvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conversationBody(theirCustomer))))
                .andExpect(status().isNotFound());
    }

    /** Citing a policy of a different customer would file the call under the wrong record. */
    @Test
    void aConversationCannotCiteAPolicyOfADifferentCustomer() throws Exception {
        String token = tokenFor(createActiveAgent());
        String customerA = customerFor(token);
        String customerB = customerFor(token);

        Map<String, Object> policyBody = new HashMap<>();
        policyBody.put("customerId", customerB);
        policyBody.put("insuranceProvider", "Example Life");
        policyBody.put("policyType", "TERM");
        policyBody.put("premiumAmount", "1000.00");
        policyBody.put("premiumFrequency", "YEARLY");

        String policy = mvc.perform(post("/api/policies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(policyBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String policyId = objectMapper.readTree(policy).get("id").asText();

        Map<String, Object> body = conversationBody(customerA);
        body.put("policyId", policyId);

        mvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAgentCannotSeeAPeersConversation() throws Exception {
        Organization org = createOrganization();
        AppUser owner = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser peer = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        String ownerToken = tokenFor(owner);
        String conversationId = start(ownerToken, customerFor(ownerToken)).get("id").asText();

        mvc.perform(get("/api/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(peer)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aManagerSeesConversationsAcrossTheOrganization() throws Exception {
        Organization org = createOrganization();
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);

        String agentToken = tokenFor(agent);
        String conversationId = start(agentToken, customerFor(agentToken)).get("id").asText();

        mvc.perform(get("/api/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager)))
                .andExpect(status().isOk());
    }

    @Test
    void aCustomersHistoryIsReadableFromTheirRecord() throws Exception {
        String token = tokenFor(createActiveAgent());
        String customerId = customerFor(token);
        start(token, customerId);
        start(token, customerId);

        String response = mvc.perform(get("/api/customers/" + customerId + "/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(response)).hasSize(2);
    }
}
