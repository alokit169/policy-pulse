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

    /**
     * Verified against this when no account matches, so that a missing account
     * costs the same time as a wrong password and cannot be detected by timing.
     */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
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

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw failedLogin(user.getOrganizationId(), user.getId(), email, "BAD_PASSWORD");
        }

        // Checked after the password so that probing a disabled account still
        // requires knowing its password.
        if (user.getStatus() != Domain.EntityStatus.ACTIVE) {
            throw failedLogin(user.getOrganizationId(), user.getId(), email, "STATUS_" + user.getStatus());
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        auditService.record(AuditAction.LOGIN_SUCCESS, ENTITY, user.getId().toString(),
                user.getOrganizationId(), user.getId(), user.getEmail(), null);

        String token = jwtService.issue(
                user.getId(), user.getEmail(), user.getRole().name(), user.getTokenVersion());
        Instant expiresAt = Instant.now().plusMillis(jwtService.getExpirationMs());
        return new LoginResponse(token, expiresAt, UserSummary.of(user));
    }

    /** Records the attempt and returns the exception for the caller to throw. */
    /**
     * Raises the user's token version, so every token already issued to them
     * stops working. Used for signing out of all devices, and the mechanism a
     * password change will reuse.
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
