package com.policypulse.auth;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.security.JwtService;
import com.policypulse.security.SecurityUtil;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    /**
     * Every failure returns this, whatever the cause. Distinguishing "no such
     * account" from "wrong password" would let anyone enumerate registered users.
     * The specific reason goes to the audit log instead.
     */
    private static final String GENERIC_FAILURE = "Invalid email or password";

    private static final String ENTITY = "User";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final LoginAttempts attempts;
    private final Clock clock;

    /**
     * Verified against this when no account matches, so that a missing account
     * costs the same time as a wrong password and cannot be detected by timing.
     */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService auditService,
                       LoginAttempts attempts, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.attempts = attempts;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim();
        Optional<AppUser> found = userRepository.findByEmailIgnoreCase(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw failedLogin(null, null, email, "USER_NOT_FOUND");
        }

        AppUser user = found.get();

        // Checked before the password, so a locked account costs an attacker a
        // request and tells them nothing: the answer is the same either way.
        if (attempts.isLocked(user)) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw failedLogin(user.getOrganizationId(), user.getId(), email, "LOCKED");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Counted in its own transaction. This one is about to roll back.
            attempts.recordFailure(user.getId());
            throw failedLogin(user.getOrganizationId(), user.getId(), email, "BAD_PASSWORD");
        }

        // Checked after the password so that probing a disabled account still
        // requires knowing its password.
        if (user.getStatus() != Domain.EntityStatus.ACTIVE) {
            throw failedLogin(user.getOrganizationId(), user.getId(), email, "STATUS_" + user.getStatus());
        }

        user.setLastLoginAt(Instant.now(clock));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        auditService.record(AuditAction.LOGIN_SUCCESS, ENTITY, user.getId().toString(),
                user.getOrganizationId(), user.getId(), user.getEmail(), null);

        String token = jwtService.issue(
                user.getId(), user.getEmail(), user.getRole().name(), user.getTokenVersion());
        Instant expiresAt = Instant.now(clock).plusMillis(jwtService.getExpirationMs());
        return new LoginResponse(token, expiresAt, UserSummary.of(user));
    }

    /**
     * Changes the caller's own password and signs them out everywhere.
     *
     * <p>The current password is required even though they are already signed
     * in: a token left open on a borrowed laptop should not be enough to take
     * the account away from its owner.
     *
     * <p>Every token already issued is revoked, including the one making this
     * request. Changing a password because it may be known to somebody else and
     * leaving their session running would defeat the point.
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        AppUser managed = userRepository.findById(SecurityUtil.currentUser().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated"));

        if (!passwordEncoder.matches(request.currentPassword(), managed.getPasswordHash())) {
            auditService.record(AuditAction.PASSWORD_CHANGE_FAILURE, ENTITY, managed.getId().toString(),
                    managed.getOrganizationId(), managed.getId(), managed.getEmail(), "BAD_PASSWORD");
            throw new ApiException(HttpStatus.FORBIDDEN, "Your current password is not correct");
        }

        if (passwordEncoder.matches(request.newPassword(), managed.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The new password must be different");
        }

        managed.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        managed.revokeIssuedTokens();
        managed.setFailedLoginAttempts(0);
        managed.setLockedUntil(null);
        userRepository.save(managed);

        auditService.record(AuditAction.PASSWORD_CHANGED, ENTITY, managed.getId().toString(),
                managed.getOrganizationId(), managed.getId(), managed.getEmail(), null);
    }

    /**
     * Raises the user's token version, so every token already issued to them
     * stops working. Used for signing out of all devices, and by a password
     * change.
     */
    @Transactional
    public void revokeAllSessions() {
        AppUser user = SecurityUtil.currentUser();
        AppUser managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        managed.revokeIssuedTokens();
        userRepository.save(managed);

        auditService.record(AuditAction.SESSIONS_REVOKED, ENTITY, managed.getId().toString(),
                managed.getOrganizationId(), managed.getId(), managed.getEmail(), null);
    }

    private ApiException failedLogin(UUID organizationId, UUID actorId, String email, String reason) {
        auditService.record(AuditAction.LOGIN_FAILURE, ENTITY,
                actorId == null ? null : actorId.toString(), organizationId, actorId, email, reason);
        return new ApiException(HttpStatus.UNAUTHORIZED, GENERIC_FAILURE);
    }
}
