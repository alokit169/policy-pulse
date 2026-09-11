package com.policypulse.auth;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.common.Domain;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing your own password.
 *
 * <p>Until now credentials could only be set by seeding or by editing the
 * database, which meant the demo accounts' published password was the only one
 * anybody had.
 */
class PasswordChangeTest extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "a-much-longer-passphrase";

    private void change(String token, String current, String replacement, int expected) throws Exception {
        mvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", current, "newPassword", replacement))))
                .andExpect(status().is(expected));
    }

    private void login(String email, String password, int expected) throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", password))))
                .andExpect(status().is(expected));
    }

    @Test
    void aPasswordCanBeChangedAndTheOldOneStopsWorking() throws Exception {
        AppUser user = createActiveAgent();

        change(tokenFor(user), TEST_PASSWORD, NEW_PASSWORD, 204);

        login(user.getEmail(), TEST_PASSWORD, 401);
        login(user.getEmail(), NEW_PASSWORD, 200);
    }

    /**
     * The reason to change a password is usually that somebody else may know it.
     * Leaving their session running would defeat the point, so every token
     * already issued stops — including the one that made the request.
     */
    @Test
    void changingAPasswordSignsEveryDeviceOut() throws Exception {
        AppUser user = createActiveAgent();
        String token = tokenFor(user);

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        change(token, TEST_PASSWORD, NEW_PASSWORD, 204);

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Being signed in is not enough. A token left open on a borrowed laptop must
     * not be enough to take the account away from its owner.
     */
    @Test
    void theCurrentPasswordIsRequiredEvenWhenSignedIn() throws Exception {
        AppUser user = createActiveAgent();

        change(tokenFor(user), "not-the-password", NEW_PASSWORD, 403);

        login(user.getEmail(), TEST_PASSWORD, 200);
    }

    @Test
    void aPasswordCannotBeReplacedWithItself() throws Exception {
        AppUser user = createActiveAgent();
        change(tokenFor(user), TEST_PASSWORD, TEST_PASSWORD, 400);
    }

    /** Length is the rule, since a passphrase beats a mangled short word. */
    @Test
    void aShortPasswordIsRefused() throws Exception {
        AppUser user = createActiveAgent();
        change(tokenFor(user), TEST_PASSWORD, "short", 400);
    }

    @Test
    void changingAPasswordNeedsCredentials() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", TEST_PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    /** Anybody signed in may change their own, whatever their role. */
    @Test
    void anAgentMayChangeTheirOwn() throws Exception {
        AppUser agent = createUser(Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        change(tokenFor(agent), TEST_PASSWORD, NEW_PASSWORD, 204);
    }
}
