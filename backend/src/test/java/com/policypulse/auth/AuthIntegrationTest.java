package com.policypulse.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.policypulse.AbstractIntegrationTest;
import com.policypulse.audit.AuditLog;
import com.policypulse.audit.AuditLogRepository;
import com.policypulse.common.Domain;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AuditLogRepository auditLogs;

    private String loginBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(email, password));
    }

    @Test
    void loginWithValidCredentialsReturnsTokenAndUser() throws Exception {
        AppUser user = createActiveAgent();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail(), TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.user.role").value("AGENT"));
    }

    @Test
    void loginResponseNeverExposesThePasswordHash() throws Exception {
        AppUser user = createActiveAgent();

        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail(), TEST_PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain(user.getPasswordHash());
    }

    @Test
    void emailCaseDoesNotAffectLogin() throws Exception {
        AppUser user = createActiveAgent();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail().toUpperCase(), TEST_PASSWORD)))
                .andExpect(status().isOk());
    }

    /**
     * An unknown account and a wrong password must be indistinguishable to the
     * caller, or the endpoint becomes a user-enumeration oracle.
     */
    @Test
    void unknownEmailAndWrongPasswordGiveTheSameResponse() throws Exception {
        AppUser user = createActiveAgent();

        String wrongPassword = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail(), "NotThePassword!")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody-" + UUID.randomUUID() + "@test.local", TEST_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        JsonNode a = objectMapper.readTree(wrongPassword);
        JsonNode b = objectMapper.readTree(unknownEmail);
        assertThat(a.get("error").asText()).isEqualTo(b.get("error").asText());
        assertThat(a.get("status").asInt()).isEqualTo(b.get("status").asInt());
    }

    @Test
    void inactiveUserCannotLogIn() throws Exception {
        AppUser user = createUser(Domain.Role.AGENT, Domain.EntityStatus.INACTIVE);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail(), TEST_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankEmailIsRejectedAsBadRequest() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("", "x")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meReturnsTheAuthenticatedUser() throws Exception {
        AppUser user = createActiveAgent();
        String token = tokenFor(user);

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.organizationId").value(user.getOrganizationId().toString()));
    }

    @Test
    void meWithoutOrWithGarbageTokenIsRejected() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The headline fix: UserDetails flags are never consulted because the filter
     * builds an already-authenticated token, so deactivating a user previously
     * left their existing token working until it expired.
     */
    @Test
    void deactivatingAUserImmediatelyInvalidatesAnAlreadyIssuedToken() throws Exception {
        AppUser user = createActiveAgent();
        String token = tokenFor(user);

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        user.setStatus(Domain.EntityStatus.INACTIVE);
        users.save(user);

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void successfulLoginIsAuditedAndStampsLastLogin() throws Exception {
        AppUser user = createActiveAgent();
        assertThat(user.getLastLoginAt()).isNull();

        tokenFor(user);

        List<AuditLog> entries = auditLogs.findByActorEmailIgnoreCaseOrderByTimestampDesc(user.getEmail());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo("LOGIN_SUCCESS");
        assertThat(entries.get(0).getOrganizationId()).isEqualTo(user.getOrganizationId());

        assertThat(users.findById(user.getId()).orElseThrow().getLastLoginAt()).isNotNull();
    }

    /**
     * The failing request rolls back, so this also proves the audit write runs in
     * its own transaction.
     */
    @Test
    void failedLoginIsAuditedWithItsReason() throws Exception {
        AppUser user = createActiveAgent();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getEmail(), "WrongPassword!")))
                .andExpect(status().isUnauthorized());

        List<AuditLog> entries = auditLogs.findByActorEmailIgnoreCaseOrderByTimestampDesc(user.getEmail());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo("LOGIN_FAILURE");
        assertThat(entries.get(0).getMetadata()).isEqualTo("BAD_PASSWORD");
    }
}
