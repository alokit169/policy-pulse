package com.policypulse.organizations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * What "today" means for a tenant.
 *
 * <p>Anything that compares a date against now has to ask this rather than the
 * server's own zone: an agency in Kolkata rolls into a new day five and a half
 * hours before a UTC server does, and reading their due dates in UTC would be
 * wrong for part of every day.
 */
@Component
public class OrganizationZones {
    private static final Logger log = LoggerFactory.getLogger(OrganizationZones.class);

    private final OrganizationRepository organizations;
    private final Clock clock;

    public OrganizationZones(OrganizationRepository organizations, Clock clock) {
        this.organizations = organizations;
        this.clock = clock;
    }

    /** Falls back to the configured default rather than failing on bad data. */
    public ZoneId zoneOf(UUID organizationId) {
        String configured = organizations.findById(organizationId)
                .map(Organization::getTimezone)
                .orElse(null);
        return parse(configured, organizationId);
    }

    public ZoneId zoneOf(Organization organization) {
        return parse(organization.getTimezone(), organization.getId());
    }

    public LocalDate today(UUID organizationId) {
        return LocalDate.now(clock.withZone(zoneOf(organizationId)));
    }

    private ZoneId parse(String configured, UUID organizationId) {
        if (configured == null) return clock.getZone();
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException ex) {
            log.warn("Organization {} has an unusable timezone '{}', using {}",
                    organizationId, configured, clock.getZone());
            return clock.getZone();
        }
    }
}
