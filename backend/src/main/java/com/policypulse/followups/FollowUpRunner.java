package com.policypulse.followups;

import com.policypulse.common.Domain;
import com.policypulse.organizations.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Drives the follow-up engine across tenants.
 *
 * <p>Separate from the engine on purpose. Looping inside it and calling the
 * per-tenant method on {@code this} would bypass the Spring proxy, and the
 * REQUIRES_NEW transaction that keeps one agency's failure off another's work
 * would silently not apply — which is how that mistake was made once already.
 */
@Component
public class FollowUpRunner {
    private static final Logger log = LoggerFactory.getLogger(FollowUpRunner.class);

    private final FollowUpEngine engine;
    private final OrganizationRepository organizations;

    public FollowUpRunner(FollowUpEngine engine, OrganizationRepository organizations) {
        this.engine = engine;
        this.organizations = organizations;
    }

    public FollowUpEngine.Result runAll() {
        FollowUpEngine.Result total = new FollowUpEngine.Result(0, 0, 0);

        for (UUID organizationId : organizations.findIdsByStatus(Domain.EntityStatus.ACTIVE)) {
            try {
                total = total.plus(engine.runForOrganization(organizationId));
            } catch (RuntimeException ex) {
                // One tenant's bad data must not stop the rest of the run.
                log.error("The follow-up engine failed for organization {}", organizationId, ex);
            }
        }

        if (total.broughtDue() > 0 || total.settled() > 0 || total.escalated() > 0) {
            log.info("Follow-ups: {} came due, {} had already been paid, {} promises were broken",
                    total.broughtDue(), total.settled(), total.escalated());
        }
        return total;
    }

    /** One tenant, for the on-demand run. */
    public FollowUpEngine.Result runFor(UUID organizationId) {
        return engine.runForOrganization(organizationId);
    }
}
