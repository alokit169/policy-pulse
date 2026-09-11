package com.policypulse.policies;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant and role isolation for policies. A policy also points at a customer, so
 * the reference itself is a way a record could cross tenants.
 */
class PolicyIsolationTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    private Map<String, Object> customerPayload() {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Asha");
        body.put("lastName", "Verma");
        body.put("phone", "+9197" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));
        return body;
    }

    private Map<String, Object> policyPayload(String customerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("insuranceProvider", "Example Life");
        body.put("policyType", "ENDOWMENT");
        body.put("premiumAmount", "12000.00");
        body.put("premiumFrequency", "YEARLY");
        body.put("policyStartDate", "2024-01-01");
        body.put("policyEndDate", "2027-01-01");
        return body;
    }

    private String createCustomer(String token) throws Exception {
        String body = mvc.perform(post("/api/customers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(customerPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createPolicy(String token, String customerId) throws Exception {
        String body = mvc.perform(post("/api/policies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(policyPayload(customerId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void aPolicyIsInvisibleToAnotherTenant() throws Exception {
        String owner = tokenFor(createActiveAgent());
        String intruder = tokenFor(createActiveAgent());
        String policyId = createPolicy(owner, createCustomer(owner));

        mvc.perform(get("/api/policies/" + policyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/policies").header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anotherTenantCannotUpdateAPolicy() throws Exception {
        String owner = tokenFor(createActiveAgent());
        AppUser other = createActiveAgent();
        String intruder = tokenFor(other);
        String policyId = createPolicy(owner, createCustomer(owner));

        // Uses a customer the intruder legitimately owns, so only the policy
        // scoping can reject this.
        String theirCustomer = createCustomer(intruder);

        mvc.perform(put("/api/policies/" + policyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(policyPayload(theirCustomer))))
                .andExpect(status().isNotFound());
    }

    /**
     * The reference is the risk: attaching a policy to a customer in another
     * tenant would both corrupt that tenant's data and confirm the customer
     * exists.
     */
    @Test
    void aPolicyCannotBeAttachedToAnotherTenantsCustomer() throws Exception {
        String owner = tokenFor(createActiveAgent());
        String outsider = tokenFor(createActiveAgent());
        String theirCustomer = createCustomer(outsider);

        mvc.perform(post("/api/policies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(policyPayload(theirCustomer))))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAgentCannotSeeAPolicyBelongingToAPeerAgent() throws Exception {
        Organization org = createOrganization();
        AppUser owner = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser peer = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        String ownerToken = tokenFor(owner);
        String policyId = createPolicy(ownerToken, createCustomer(ownerToken));

        mvc.perform(get("/api/policies/" + policyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(peer)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/policies").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(peer)))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aManagerSeesEveryPolicyInTheirOrganization() throws Exception {
        Organization org = createOrganization();
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);

        String agentToken = tokenFor(agent);
        String policyId = createPolicy(agentToken, createCustomer(agentToken));

        mvc.perform(get("/api/policies/" + policyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/policies").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager)))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void anotherTenantCannotReadOrPayThePremiumSchedule() throws Exception {
        String owner = tokenFor(createActiveAgent());
        String intruder = tokenFor(createActiveAgent());
        String policyId = createPolicy(owner, createCustomer(owner));

        String schedule = mvc.perform(get("/api/policies/" + policyId + "/premiums")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String premiumId = objectMapper.readTree(schedule).get(0).get("id").asText();

        mvc.perform(get("/api/policies/" + policyId + "/premiums")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/policies/" + policyId + "/premiums/" + premiumId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerPoliciesFollowCustomerVisibility() throws Exception {
        String owner = tokenFor(createActiveAgent());
        String intruder = tokenFor(createActiveAgent());
        String customerId = createCustomer(owner);
        createPolicy(owner, customerId);

        mvc.perform(get("/api/customers/" + customerId + "/policies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/customers/" + customerId + "/policies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());
    }

    @Test
    void policyRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/policies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/policies/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/policies").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
