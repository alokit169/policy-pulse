package com.policypulse.auth;

import com.policypulse.common.Domain;
import com.policypulse.users.AppUser;

import java.util.UUID;

/** The current user as exposed to clients. Never carries the password hash. */
public record UserSummary(
        UUID id,
        UUID organizationId,
        String name,
        String email,
        Domain.Role role) {

    public static UserSummary of(AppUser user) {
        return new UserSummary(
                user.getId(), user.getOrganizationId(), user.getName(), user.getEmail(), user.getRole());
    }
}
