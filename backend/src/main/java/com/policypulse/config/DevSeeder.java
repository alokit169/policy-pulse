package com.policypulse.config;

import com.policypulse.common.Domain;
import com.policypulse.organizations.Organization;
import com.policypulse.organizations.OrganizationRepository;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal demo tenant so the app is usable immediately. Enabled by app.seed,
 * which docker-compose sets for local runs. Idempotent: restarting never
 * duplicates or overwrites. Richer sample data belongs to the seeding phase.
 */
@Component
@ConditionalOnProperty(name = "app.seed", havingValue = "true")
public class DevSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    private static final String ORG_NAME = "Demo Insurance Agency";
    private static final String ADMIN_EMAIL = "admin@demo.local";
    private static final String AGENT_EMAIL = "agent@demo.local";
    private static final String DEMO_PASSWORD = "Password123!";

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DevSeeder(OrganizationRepository organizations, UserRepository users, PasswordEncoder passwordEncoder) {
        this.organizations = organizations;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (users.findByEmailIgnoreCase(ADMIN_EMAIL).isPresent()) {
            log.info("Demo data already present, skipping seed");
            return;
        }

        Organization org = new Organization();
        org.setName(ORG_NAME);
        org.setEmail("contact@demo.local");
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        AppUser admin = user(org, "Demo Admin", ADMIN_EMAIL, Domain.Role.ORGANIZATION_ADMIN);
        users.save(admin);

        AppUser agent = user(org, "Demo Agent", AGENT_EMAIL, Domain.Role.AGENT);
        agent.setManagerId(admin.getId());
        users.save(agent);

        log.info("Seeded organization '{}' with users {} and {}", ORG_NAME, ADMIN_EMAIL, AGENT_EMAIL);
        log.warn("Demo accounts use a well-known password. Never enable app.seed outside development.");
    }

    private AppUser user(Organization org, String name, String email, Domain.Role role) {
        AppUser user = new AppUser();
        user.setOrganizationId(org.getId());
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(Domain.EntityStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        return user;
    }
}
