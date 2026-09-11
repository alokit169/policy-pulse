package com.policypulse.reminders;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves a tenant's reminder settings, creating the defaults on first use.
 *
 * <p>One organization has at most one configuration, enforced by a unique
 * constraint. Looking it up and then inserting is a check-then-act, so two
 * simultaneous first visits would both find nothing and both try to insert. The
 * insert runs in its own transaction and a lost race re-reads rather than
 * failing, so the caller never sees a conflict for simply opening the page.
 */
@Component
public class ReminderConfigurations {
    private final ReminderConfigurationRepository configurations;

    public ReminderConfigurations(ReminderConfigurationRepository configurations) {
        this.configurations = configurations;
    }

    @Transactional
    public ReminderConfiguration forOrganization(UUID organizationId) {
        return configurations.findByOrganizationId(organizationId)
                .orElseGet(() -> createDefaults(organizationId));
    }

    /**
     * Separate transaction so a constraint violation here does not poison the
     * caller's, which would otherwise be unable to re-read.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReminderConfiguration createDefaults(UUID organizationId) {
        try {
            ReminderConfiguration created = new ReminderConfiguration();
            created.setOrganizationId(organizationId);
            return configurations.saveAndFlush(created);
        } catch (DataIntegrityViolationException raced) {
            return configurations.findByOrganizationId(organizationId).orElseThrow(() -> raced);
        }
    }
}
