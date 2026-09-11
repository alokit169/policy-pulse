package com.policypulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.policypulse.common.Domain;
import com.policypulse.organizations.Organization;
import com.policypulse.organizations.OrganizationRepository;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Boots the full application against the Testcontainers Postgres. Subclasses
 * share one context and one container for the whole run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
public abstract class AbstractIntegrationTest {

    protected static final String TEST_PASSWORD = "Password123!";

    @Autowired protected MockMvc mvc;
    @Autowired protected UserRepository users;
    @Autowired protected OrganizationRepository organizations;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected ObjectMapper objectMapper;

    protected Organization createOrganization() {
        Organization org = new Organization();
        org.setName("Test Org " + UUID.randomUUID());
        return organizations.save(org);
    }

    /**
     * Creates a user inside an existing organization, for tests that need two
     * users who are tenant peers.
     */
    protected AppUser createUserIn(UUID organizationId, Domain.Role role, Domain.EntityStatus status) {
        AppUser user = new AppUser();
        user.setOrganizationId(organizationId);
        user.setName("Test User");
        // Unique per call so tests never collide on the global unique email index.
        user.setEmail("user-" + UUID.randomUUID() + "@test.local");
        user.setRole(role);
        user.setStatus(status);
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        return users.save(user);
    }

    /** Creates a user in a brand new organization. */
    protected AppUser createUser(Domain.Role role, Domain.EntityStatus status) {
        return createUserIn(createOrganization().getId(), role, status);
    }

    protected AppUser createActiveAgent() {
        return createUser(Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
    }

    /** Logs the user in through the real endpoint and returns the bearer token. */
    protected String tokenFor(AppUser user) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", TEST_PASSWORD))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
