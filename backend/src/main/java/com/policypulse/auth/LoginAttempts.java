package com.policypulse.auth;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Remembers wrong guesses at an account.
 *
 * <p>Its own bean, and its own transaction, because a failed login ends by
 * throwing. Counting inside the login transaction would write the count and then
 * roll it straight back with the exception, so an account would never reach the
 * limit and would never lock — which is exactly the shape the audit log already
 * had to be built around. Called from another bean so the proxy applies and
 * REQUIRES_NEW actually takes effect.
 */
@Component
public class LoginAttempts {

    /**
     * Consecutive failures an account will answer before it stops answering.
     * Rate limiting is per client IP and does nothing about a slow attack spread
     * across addresses at one account; this bounds the guesses that account will
     * ever take.
     */
    public static final int MAX_FAILED = 10;

    /**
     * How long it then stops for. Long enough to make guessing hopeless, short
     * enough that somebody who has merely forgotten their password is not shut
     * out for the day by an attack aimed at them.
     */
    public static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;

    public LoginAttempts(UserRepository users, AuditService audit, Clock clock) {
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean isLocked(AppUser user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now(clock));
    }

    /**
     * Counts a wrong password, and locks the account once there have been enough.
     *
     * <p>Both steps happen in the database rather than in memory. Counting in
     * memory is a read, an add and a write, and ten guesses arriving together all
     * read zero and all write one — the account never reaches the limit and the
     * whole control does nothing. Locking conditionally does the same job for the
     * audit record: whichever guess crosses the line writes it, once.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID userId) {
        Instant now = Instant.now(clock);
        if (users.countFailedLogin(userId, now) == 0) {
            return;
        }

        if (users.lockIfOverLimit(userId, MAX_FAILED, now.plus(LOCKOUT)) == 0) {
            return;
        }

        users.findById(userId).ifPresent(user ->
                audit.record(AuditAction.ACCOUNT_LOCKED, "User", user.getId().toString(),
                        user.getOrganizationId(), user.getId(), user.getEmail(),
                        user.getFailedLoginAttempts() + " consecutive failures"));
    }
}
