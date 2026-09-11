package com.policypulse.dashboard;

import com.policypulse.common.Domain;
import com.policypulse.dashboard.DashboardResponse.ActionItem;
import com.policypulse.dashboard.DashboardResponse.Money;
import com.policypulse.dashboard.DashboardResponse.Scope;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class DashboardService {

    /** Enough to act on without turning the dashboard into a work queue. */
    private static final Pageable TOP_ACTION_ITEMS = PageRequest.of(0, 10);

    private static final int DUE_SOON_DAYS = 7;

    private final DashboardRepository dashboard;
    private final OrganizationZones zones;
    private final Clock clock;

    public DashboardService(DashboardRepository dashboard, OrganizationZones zones, Clock clock) {
        this.dashboard = dashboard;
        this.zones = zones;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardResponse forCurrentUser() {
        AuthUser caller = SecurityUtil.current();
        UUID orgId = caller.getOrganizationId();

        // Overdue is a comparison against a date, so it has to be the tenant's
        // date. On a UTC server an agency in Kolkata would otherwise see a
        // premium as overdue five and a half hours early.
        ZoneId zone = zones.zoneOf(orgId);
        LocalDate today = zones.today(orgId);

        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        boolean ownBookOnly = caller.role() == Domain.Role.AGENT;
        UUID agentId = caller.getId();

        Money overdue = Money.of(ownBookOnly
                ? dashboard.overdueForAgent(orgId, agentId, today)
                : dashboard.overdueForOrganization(orgId, today));

        Money dueSoon = Money.of(ownBookOnly
                ? dashboard.dueBetweenForAgent(orgId, agentId, today, today.plusDays(DUE_SOON_DAYS))
                : dashboard.dueBetweenForOrganization(orgId, today, today.plusDays(DUE_SOON_DAYS)));

        Money collected = Money.of(ownBookOnly
                ? dashboard.collectedForAgent(orgId, agentId, monthStart, monthEnd)
                : dashboard.collectedForOrganization(orgId, monthStart, monthEnd));

        List<DashboardRepository.OverdueItem> oldest = ownBookOnly
                ? dashboard.oldestOverdueForAgent(orgId, agentId, today, TOP_ACTION_ITEMS)
                : dashboard.oldestOverdueForOrganization(orgId, today, TOP_ACTION_ITEMS);

        return new DashboardResponse(
                ownBookOnly ? Scope.OWN_BOOK : Scope.ORGANIZATION,
                today,
                zone.getId(),
                ownBookOnly
                        ? dashboard.activeCustomersForAgent(orgId, agentId)
                        : dashboard.activeCustomersForOrganization(orgId),
                ownBookOnly
                        ? dashboard.activePoliciesForAgent(orgId, agentId)
                        : dashboard.activePoliciesForOrganization(orgId),
                ownBookOnly
                        ? dashboard.pendingRemindersForAgent(orgId, agentId)
                        : dashboard.pendingRemindersForOrganization(orgId),
                ownBookOnly
                        ? dashboard.followUpsDueForAgent(orgId, agentId, Instant.now(clock))
                        : dashboard.followUpsDueForOrganization(orgId, Instant.now(clock)),
                overdue,
                dueSoon,
                collected,
                oldest.stream().map(item -> toActionItem(item, today)).toList());
    }

    private ActionItem toActionItem(DashboardRepository.OverdueItem item, LocalDate today) {
        return new ActionItem(
                item.getPremiumId(),
                item.getPolicyId(),
                item.getCustomerId(),
                item.getCustomerName(),
                item.getPolicyNumber(),
                item.getCurrencyCode(),
                item.getAmount(),
                item.getDueDate(),
                ChronoUnit.DAYS.between(item.getDueDate(), today));
    }
}
