package com.policypulse.security;

import com.policypulse.common.Domain;
import com.policypulse.users.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class AuthUser implements UserDetails {
    private final AppUser user;

    public AuthUser(AppUser user) {
        this.user = user;
    }

    public AppUser getUser() { return user; }
    public UUID getId() { return user.getId(); }
    public UUID getOrganizationId() { return user.getOrganizationId(); }
    public Domain.Role role() { return user.getRole(); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getEmail(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return user.getStatus() != Domain.EntityStatus.SUSPENDED; }
    @Override public boolean isCredentialsNonExpired() { return true; }

    /**
     * Note: JwtAuthFilter builds an already-authenticated token, so Spring never
     * runs a UserDetailsChecker over these flags. The filter enforces status
     * itself; this stays correct for any code path that does consult it.
     */
    @Override public boolean isEnabled() { return user.getStatus() == Domain.EntityStatus.ACTIVE; }
}
