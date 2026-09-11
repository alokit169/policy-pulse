package com.policypulse.customers;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant and role isolation. These are the tests that matter most in this
 * phase: a leak here exposes one agency's book to another.
 *
 * <p>Every denial is expected to be 404 rather than 403, because 403 would
 * confirm that the record exists.
 */
class CustomerIsolationTest extends AbstractIntegrationTest {

    private Map<String, Object> customerPayload() {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Asha");
        body.put("lastName", "Verma");
        // Unique per call: phone is unique per tenant.
        body.put("phone", "+9198" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100000000)));
        return body;
    }

    private String createCustomerAs(String token) throws Exception {
        String body = mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(customerPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void aCustomerIsInvisibleToAnotherTenant() throws Exception {
        AppUser tenantA = createActiveAgent();
        AppUser tenantB = createActiveAgent();
        String customerId = createCustomerAs(tokenFor(tenantA));
        String intruder = tokenFor(tenantB);

        mvc.perform(get("/api/customers/" + customerId).header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantCannotUpdateOrArchiveACustomer() throws Exception {
        AppUser tenantA = createActiveAgent();
        AppUser tenantB = createActiveAgent();
        String customerId = createCustomerAs(tokenFor(tenantA));
        String intruder = tokenFor(tenantB);

        mvc.perform(put("/api/customers/" + customerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(customerPayload())))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/customers/" + customerId).header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchNeverReturnsAnotherTenantsCustomers() throws Exception {
        AppUser tenantA = createActiveAgent();
        AppUser tenantB = createActiveAgent();
        createCustomerAs(tokenFor(tenantA));

        mvc.perform(get("/api/customers").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(tenantB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void anAgentCannotSeeACustomerBelongingToAPeerAgent() throws Exception {
        Organization org = createOrganization();
        AppUser owner = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser peer = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        String customerId = createCustomerAs(tokenFor(owner));

        mvc.perform(get("/api/customers/" + customerId).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(peer)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/customers").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(peer)))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aManagerSeesEveryCustomerInTheirOrganization() throws Exception {
        Organization org = createOrganization();
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);

        String customerId = createCustomerAs(tokenFor(agent));

        mvc.perform(get("/api/customers/" + customerId).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/customers").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager)))
                .andExpect(jsonPath("$.total").value(1));
    }

    /** An agent must not be able to hand a customer to someone else. */
    @Test
    void anAgentCannotAssignACustomerToAnotherUser() throws Exception {
        Organization org = createOrganization();
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser peer = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        Map<String, Object> body = customerPayload();
        body.put("assignedAgentId", peer.getId().toString());

        mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedAgentId").value(agent.getId().toString()));
    }

    /** Assigning across tenants would move a record out of its organization. */
    @Test
    void aManagerCannotAssignACustomerToAnAgentInAnotherTenant() throws Exception {
        Organization org = createOrganization();
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);
        AppUser outsider = createActiveAgent();

        Map<String, Object> body = customerPayload();
        body.put("assignedAgentId", outsider.getId().toString());

        mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aManagerMayAssignACustomerToAnAgentInTheirOwnTenant() throws Exception {
        Organization org = createOrganization();
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        Map<String, Object> body = customerPayload();
        body.put("assignedAgentId", agent.getId().toString());

        mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedAgentId").value(agent.getId().toString()));
    }

    @Test
    void customerRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
