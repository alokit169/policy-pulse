package com.policypulse.customers;

import com.fasterxml.jackson.databind.JsonNode;
import com.policypulse.AbstractIntegrationTest;
import com.policypulse.audit.AuditLog;
import com.policypulse.audit.AuditLogRepository;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerCrudTest extends AbstractIntegrationTest {

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    @Autowired private AuditLogRepository auditLogs;

    private AppUser agent;
    private String token;

    @BeforeEach
    void signIn() throws Exception {
        agent = createActiveAgent();
        token = tokenFor(agent);
    }

    /** Phone is unique per tenant, so every payload needs its own. */
    private String uniquePhone() {
        return String.format("+9198%08d", PHONE_SEQ.incrementAndGet() + (int) (System.nanoTime() % 1_000_000));
    }

    private Map<String, Object> payload(String firstName, String lastName) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("phone", uniquePhone());
        return body;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        String response = mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void createFillsInDefaultsAndGeneratesACustomerNumber() throws Exception {
        JsonNode created = create(payload("Asha", "Verma"));

        assertThat(created.get("customerNumber").asText()).startsWith("C-");
        assertThat(created.get("assignedAgentId").asText()).isEqualTo(agent.getId().toString());
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("communicationConsent").asBoolean()).isTrue();
        assertThat(created.get("optedOut").asBoolean()).isFalse();
        assertThat(created.get("preferredLanguage").asText()).isEqualTo("en");
        assertThat(created.get("fullName").asText()).isEqualTo("Asha Verma");
    }

    @Test
    void theSamePhoneCannotBeUsedTwiceInATenant() throws Exception {
        Map<String, Object> first = payload("Asha", "Verma");
        create(first);

        Map<String, Object> second = payload("Ravi", "Kumar");
        second.put("phone", first.get("phone"));

        mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Another customer already uses this phone number"));
    }

    @Test
    void anExplicitCustomerNumberCannotBeReused() throws Exception {
        Map<String, Object> first = payload("Asha", "Verma");
        first.put("customerNumber", "CUST-001");
        create(first);

        Map<String, Object> second = payload("Ravi", "Kumar");
        second.put("customerNumber", "CUST-001");

        mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidPayloadsAreRejected() throws Exception {
        Map<String, Object> noName = payload("", "Verma");
        mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noName)))
                .andExpect(status().isBadRequest());

        Map<String, Object> badPhone = payload("Asha", "Verma");
        badPhone.put("phone", "abc");
        mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badPhone)))
                .andExpect(status().isBadRequest());

        Map<String, Object> badEmail = payload("Asha", "Verma");
        badEmail.put("email", "not-an-email");
        mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badEmail)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateChangesTheStoredFields() throws Exception {
        String id = create(payload("Asha", "Verma")).get("id").asText();

        Map<String, Object> update = payload("Asha", "Sharma");
        update.put("email", "asha@example.com");
        update.put("address", "12 Market Road");

        mvc.perform(put("/api/customers/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Sharma"))
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.address").value("12 Market Road"));
    }

    @Test
    void archiveKeepsTheRecordAndRestoreBringsItBack() throws Exception {
        String id = create(payload("Asha", "Verma")).get("id").asText();

        mvc.perform(delete("/api/customers/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        // Archived, not deleted: still readable, and excluded from an active filter.
        mvc.perform(get("/api/customers/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mvc.perform(get("/api/customers").param("status", "ACTIVE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(post("/api/customers/" + id + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void searchMatchesNameAndPhoneAndCustomerNumber() throws Exception {
        JsonNode asha = create(payload("Asha", "Verma"));
        create(payload("Ravi", "Kumar"));

        mvc.perform(get("/api/customers").param("q", "asha")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].firstName").value("Asha"));

        mvc.perform(get("/api/customers").param("q", "KUMAR")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].lastName").value("Kumar"));

        mvc.perform(get("/api/customers").param("q", asha.get("customerNumber").asText())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(1));

        // No term returns everything the caller may see.
        mvc.perform(get("/api/customers").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void resultsArePagedAndSortedByName() throws Exception {
        create(payload("Zoya", "Zaman"));
        create(payload("Asha", "Verma"));
        create(payload("Ravi", "Kumar"));

        mvc.perform(get("/api/customers").param("size", "2").param("page", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                // Default sort is last name ascending: Kumar, Verma, Zaman.
                .andExpect(jsonPath("$.items[0].lastName").value("Kumar"));

        mvc.perform(get("/api/customers").param("size", "2").param("page", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].lastName").value("Zaman"));
    }

    @Test
    void missingCustomerIsNotFound() throws Exception {
        mvc.perform(get("/api/customers/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void changesAreAudited() throws Exception {
        String id = create(payload("Asha", "Verma")).get("id").asText();

        mvc.perform(delete("/api/customers/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        List<AuditLog> entries = auditLogs.findByActorEmailIgnoreCaseOrderByTimestampDesc(agent.getEmail());
        List<String> actions = entries.stream().map(AuditLog::getAction).toList();

        assertThat(actions).contains("CUSTOMER_CREATED", "CUSTOMER_ARCHIVED");
        assertThat(entries).allSatisfy(entry ->
                assertThat(entry.getOrganizationId()).isEqualTo(agent.getOrganizationId()));
    }

    /**
     * A search term is user input, so LIKE metacharacters in it must match
     * literally. Without escaping, searching for "%" returned every customer.
     */
    @Test
    void likeWildcardsInASearchTermAreMatchedLiterally() throws Exception {
        create(payload("Asha", "Verma"));
        create(payload("Ravi", "Kumar"));

        mvc.perform(get("/api/customers").param("q", "%")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/customers").param("q", "_")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(0));

        // The escape character itself is not special to the caller either.
        mvc.perform(get("/api/customers").param("q", "!")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aLiteralPercentInANameIsFound() throws Exception {
        create(payload("Disc%unt", "Shop"));

        mvc.perform(get("/api/customers").param("q", "disc%unt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].firstName").value("Disc%unt"));
    }
}
