package com.insureplatform.security;

import com.insureplatform.common.ApiException;
import com.insureplatform.common.Domain;
import com.insureplatform.users.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class SecurityUtil {
    private SecurityUtil() {}

    public static AuthUser current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }

    public static AppUser currentUser() {
        return current().getUser();
    }

    public static UUID orgId() {
        return current().getOrganizationId();
    }

    public static boolean isAgent() {
        return currentUser().getRole() == Domain.Role.AGENT;
    }

    public static boolean canManageOrg() {
        Domain.Role r = currentUser().getRole();
        return r == Domain.Role.SUPER_ADMIN || r == Domain.Role.ORGANIZATION_ADMIN || r == Domain.Role.MANAGER;
    }
}
