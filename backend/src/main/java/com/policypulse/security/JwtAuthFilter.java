package com.policypulse.security;

import com.policypulse.common.Domain;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            authenticate(header.substring(BEARER.length()));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Claims claims = jwtService.parse(token);
            UUID userId = UUID.fromString(claims.getSubject());
            Optional<AppUser> found = userRepository.findById(userId);

            if (found.isEmpty()) {
                // The account was removed after the token was issued.
                log.debug("Rejecting token for unknown user {}", userId);
                SecurityContextHolder.clearContext();
                return;
            }

            AppUser user = found.get();
            // Status is checked on every request, not just at login: a token
            // issued before the account was deactivated must stop working
            // immediately rather than at its natural expiry.
            if (user.getStatus() != Domain.EntityStatus.ACTIVE) {
                log.debug("Rejecting token for {} user {}", user.getStatus(), userId);
                SecurityContextHolder.clearContext();
                return;
            }

            AuthUser principal = new AuthUser(user);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        } catch (Exception ex) {
            // Expired, malformed or wrongly signed. Debug rather than warn: an
            // expired token is routine, and this is attacker-reachable.
            log.debug("Rejecting bearer token: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
        }
    }
}
