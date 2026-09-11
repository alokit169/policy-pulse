package com.policypulse.auth;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.common.Domain;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An account stops answering after enough wrong guesses.
 *
 * <p>Rate limiting is per client IP, which does nothing about a slow attack
 * spread across addresses at one account. This is what bounds the number of
 * guesses that account will ever take.
 */
class LoginLockoutTest extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;

    private void attempt(String email, String password, int expectedStatus) throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", password))))
                .andExpect(status().is(expectedStatus));
    }

    private AppUser reloaded(AppUser user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /**
     * The counter has to survive the request that increments it. Login runs in a
     * transaction and then throws, so a count written inside it would be rolled
     * back with everything else and the account would never lock at all.
     */
    @Test
    void wrongGuessesAreRememberedAcrossRequests() throws Exception {
        AppUser user = createActiveAgent();

        attempt(user.getEmail(), "not-the-password", 401);
        assertThat(reloaded(user).getFailedLoginAttempts()).isEqualTo(1);

        attempt(user.getEmail(), "not-the-password", 401);
        assertThat(reloaded(user).getFailedLoginAttempts()).isEqualTo(2);
    }

    @Test
    void anAccountStopsAnsweringAfterTooManyWrongGuesses() throws Exception {
        AppUser user = createActiveAgent();

        for (int i = 0; i < LoginAttempts.MAX_FAILED; i++) {
            attempt(user.getEmail(), "not-the-password", 401);
        }

        assertThat(reloaded(user).getLockedUntil()).isNotNull();

        // The right password, refused, because the account is not answering.
        attempt(user.getEmail(), TEST_PASSWORD, 401);
    }

    /**
     * A locked account answers exactly what an unlocked one with a wrong password
     * answers. Anything else would tell an attacker they had found a real account
     * and were close enough to be worth locking out.
     */
    @Test
    void beingLockedIsNotSomethingTheAnswerReveals() throws Exception {
        AppUser locked = createActiveAgent();
        for (int i = 0; i < LoginAttempts.MAX_FAILED; i++) {
            attempt(locked.getEmail(), "not-the-password", 401);
        }

        String lockedBody = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", locked.getEmail(), "password", TEST_PASSWORD))))
                .andReturn().getResponse().getContentAsString();

        String unknownBody = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "nobody@test.local", "password", "whatever"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(errorOf(lockedBody)).isEqualTo(errorOf(unknownBody));
    }

    /** Signing in successfully clears the count, so a near miss is not cumulative. */
    @Test
    void signingInClearsWhatWentBefore() throws Exception {
        AppUser user = createActiveAgent();

        attempt(user.getEmail(), "not-the-password", 401);
        attempt(user.getEmail(), "not-the-password", 401);
        attempt(user.getEmail(), TEST_PASSWORD, 200);

        AppUser after = reloaded(user);
        assertThat(after.getFailedLoginAttempts()).isZero();
        assertThat(after.getLockedUntil()).isNull();
    }

    /** A disabled account is still not distinguishable, lock or no lock. */
    @Test
    void aDisabledAccountIsNotLockedIntoSayingSo() throws Exception {
        AppUser user = createUser(Domain.Role.AGENT, Domain.EntityStatus.INACTIVE);

        attempt(user.getEmail(), TEST_PASSWORD, 401);

        assertThat(reloaded(user).getFailedLoginAttempts())
                .as("the password was right, so it is not a failed guess")
                .isZero();
    }

    private String errorOf(String body) throws Exception {
        return objectMapper.readTree(body).path("error").asText();
    }
}
