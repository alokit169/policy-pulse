package com.insureplatform.security;

import com.insureplatform.users.AppUser;
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
    public DomainRole role() { return DomainRole.from(user.getRole().name()); }

    public enum DomainRole {
        SUPER_ADMIN, ORGANIZATION_ADMIN, MANAGER, AGENT;
        static DomainRole from(String n) { return DomainRole.valueOf(n); }
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getEmail(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
