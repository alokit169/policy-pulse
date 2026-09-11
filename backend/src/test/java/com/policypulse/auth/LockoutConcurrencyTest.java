package com.policypulse.auth;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Counting wrong guesses, when they do not arrive one at a time.
 *
 * <p>An attacker has no reason to be polite about it, and a limit that only holds
 * for sequential guesses is not a limit. The fourth place in this project where a
 * decision was read a moment before it was written; measured here, ten guesses
 * counted as one and the account never locked.
 */
class LockoutConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private LoginAttempts attempts;

    @Test
    void guessesArrivingTogetherAreAllCounted() throws Exception {
        AppUser user = createActiveAgent();

        int threads = LoginAttempts.MAX_FAILED;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    attempts.recordFailure(user.getId());
                } catch (Exception ignored) {
                    // A loser that fails outright is fine; a loser that is
                    // silently not counted is the thing being looked for.
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        AppUser after = userRepository.findById(user.getId()).orElseThrow();
        assertThat(after.getFailedLoginAttempts())
                .as("every guess counted, however they arrived")
                .isEqualTo(threads);
        assertThat(after.getLockedUntil()).isNotNull();
    }

    /**
     * What a lock running out means. Ten wrong passwords lock an account for
     * fifteen minutes, so afterwards it has to be another ten: leaving the count
     * where it was gives whoever forgot their password one attempt every fifteen
     * minutes for the rest of the day.
     */
    @Test
    void aLockThatHasExpiredGivesTheOwnerTheirAttemptsBack() {
        AppUser user = createActiveAgent();

        for (int i = 0; i < LoginAttempts.MAX_FAILED; i++) {
            attempts.recordFailure(user.getId());
        }

        // The lock runs out.
        AppUser locked = userRepository.findById(user.getId()).orElseThrow();
        locked.setLockedUntil(Instant.now().minusSeconds(60));
        userRepository.save(locked);

        // Somebody mistypes once more.
        attempts.recordFailure(user.getId());

        AppUser after = userRepository.findById(user.getId()).orElseThrow();
        assertThat(after.getFailedLoginAttempts())
                .as("counting starts again")
                .isEqualTo(1);
        assertThat(attempts.isLocked(after))
                .as("one mistype after the wait must not cost another fifteen minutes")
                .isFalse();
    }
}
