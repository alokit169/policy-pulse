package com.policypulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.policypulse.common.Domain;
import com.policypulse.organizations.Organization;
import com.policypulse.organizations.OrganizationRepository;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import com.policypulse.messaging.StubMessageProvider;
import com.policypulse.voice.StubVoiceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Boots the full application against the Testcontainers Postgres. Subclasses
 * share one context and one container for the whole run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class,
        com.policypulse.ai.StubAiConfiguration.class,
        com.policypulse.voice.StubVoiceConfiguration.class,
        com.policypulse.messaging.StubMessagingConfiguration.class})
public abstract class AbstractIntegrationTest {

    protected static final String TEST_PASSWORD = "Password123!";

    @Autowired protected MockMvc mvc;
    @Autowired protected UserRepository users;
    @Autowired protected OrganizationRepository organizations;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired private List<StubMessageProvider> messageStubs;
    @Autowired private StubVoiceProvider voiceStub;

    /**
     * The stubs are singletons shared by every test in the context, and they
     * remember both what they were told to return and how often they were used.
     * Left alone, a test that does not set them up inherits whatever the previous
     * one wanted, which makes failures depend on the order tests happen to run
     * in. Cleared before each so a test only sees what it asked for.
     */
    @BeforeEach
    void resetStubProviders() {
        messageStubs.forEach(StubMessageProvider::reset);
        voiceStub.reset();
    }

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
