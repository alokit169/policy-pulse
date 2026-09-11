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
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final String TEST_PASSWORD = "Password123!";

    @Autowired protected MockMvc mvc;
    @Autowired protected UserRepository users;
    @Autowired protected OrganizationRepository organizations;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected ObjectMapper objectMapper;

    /**
     * Creates an organization and a user in it. The email is unique per call so
     * tests never collide on the global unique email index.
     */
    protected AppUser createUser(Domain.Role role, Domain.EntityStatus status) {
        Organization org = new Organization();
        org.setName("Test Org");
        organizations.save(org);

        AppUser user = new AppUser();
        user.setOrganizationId(org.getId());
        user.setName("Test User");
        user.setEmail("user-" + UUID.randomUUID() + "@test.local");
        user.setRole(role);
        user.setStatus(status);
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        return users.save(user);
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
