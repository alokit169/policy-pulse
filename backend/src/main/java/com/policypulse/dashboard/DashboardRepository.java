package com.policypulse.dashboard;

import com.policypulse.premiums.PremiumPayment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregates for the dashboard.
 *
 * <p>Everything here counts and sums in the database. Loading a tenant's
 * premiums to add them up in Java would get slower with every policy written,
 * and the dashboard is the page opened most often.
 *
 * <p>Each query has an organization-wide and an agent-scoped form rather than a
 * nullable agent parameter. An untyped null parameter is what broke the customer
 * search the first time it ran, and being explicit costs only a few lines. The
 * agent-scoped forms join the policy because that is where the agent is
 * recorded; the organization-wide ones need no join at all.
 *
 * <p>Buckets are worked out from dates rather than stored status. An instalment's
 * status is written when the schedule is generated and is not rewritten as days
 * pass, so an untouched row can still say UPCOMING after its due date. The date
 * is the truth.
 *
 * <p>Summing across policies is only meaningful because every policy is in
 * rupees, which a check constraint enforces.
 */
public interface DashboardRepository extends Repository<PremiumPayment, UUID> {

    /** A count and a money total. */
    interface MoneySummary {
        long getItemCount();

        BigDecimal getTotalAmount();
    }

    /** One thing needing attention, flattened so no entities are loaded. */
    interface OverdueItem {
        UUID getPremiumId();

        UUID getPolicyId();

        UUID getCustomerId();

        String getCustomerName();

        String getPolicyNumber();

        String getCurrencyCode();

        BigDecimal getAmount();

        LocalDate getDueDate();
    }

    @Query("""
            SELECT COUNT(p) AS itemCount, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM PremiumPayment p
            WHERE p.organizationId = :org
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.WAIVED
              AND p.dueDate < :today
            """)
    MoneySummary overdueForOrganization(@Param("org") UUID organizationId, @Param("today") LocalDate today);

    @Query("""
            SELECT COUNT(p) AS itemCount, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM PremiumPayment p JOIN Policy pol ON pol.id = p.policyId
            WHERE p.organizationId = :org AND pol.agentId = :agent
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.WAIVED
              AND p.dueDate < :today
            """)
    MoneySummary overdueForAgent(@Param("org") UUID organizationId, @Param("agent") UUID agentId,
                                 @Param("today") LocalDate today);

    @Query("""
            SELECT COUNT(p) AS itemCount, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM PremiumPayment p
            WHERE p.organizationId = :org
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.WAIVED
              AND p.dueDate BETWEEN :from AND :to
            """)
    MoneySummary dueBetweenForOrganization(@Param("org") UUID organizationId,
                                           @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            SELECT COUNT(p) AS itemCount, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM PremiumPayment p JOIN Policy pol ON pol.id = p.policyId
            WHERE p.organizationId = :org AND pol.agentId = :agent
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.WAIVED
              AND p.dueDate BETWEEN :from AND :to
            """)
    MoneySummary dueBetweenForAgent(@Param("org") UUID organizationId, @Param("agent") UUID agentId,
                                    @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            SELECT COUNT(p) AS itemCount, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM PremiumPayment p
            WHERE p.organizationId = :org
              AND p.status = com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.paidDate BETWEEN :from AND :to
            """)
    MoneySummary collectedForOrganization(@Param("org") UUID organizationId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            SELECT COUNT(p) AS itemCount, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM PremiumPayment p JOIN Policy pol ON pol.id = p.policyId
            WHERE p.organizationId = :org AND pol.agentId = :agent
              AND p.status = com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.paidDate BETWEEN :from AND :to
            """)
    MoneySummary collectedForAgent(@Param("org") UUID organizationId, @Param("agent") UUID agentId,
                                   @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            SELECT p.id AS premiumId, pol.id AS policyId, c.id AS customerId,
                   CONCAT(c.firstName, ' ', c.lastName) AS customerName,
                   pol.policyNumber AS policyNumber, pol.currencyCode AS currencyCode,
                   p.amount AS amount, p.dueDate AS dueDate
            FROM PremiumPayment p
              JOIN Policy pol ON pol.id = p.policyId
              JOIN Customer c ON c.id = pol.customerId
            WHERE p.organizationId = :org
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.WAIVED
              AND p.dueDate < :today
            ORDER BY p.dueDate ASC
            """)
    List<OverdueItem> oldestOverdueForOrganization(@Param("org") UUID organizationId,
                                                   @Param("today") LocalDate today, Pageable limit);

    @Query("""
            SELECT p.id AS premiumId, pol.id AS policyId, c.id AS customerId,
                   CONCAT(c.firstName, ' ', c.lastName) AS customerName,
                   pol.policyNumber AS policyNumber, pol.currencyCode AS currencyCode,
                   p.amount AS amount, p.dueDate AS dueDate
            FROM PremiumPayment p
              JOIN Policy pol ON pol.id = p.policyId
              JOIN Customer c ON c.id = pol.customerId
            WHERE p.organizationId = :org AND pol.agentId = :agent
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.WAIVED
              AND p.dueDate < :today
            ORDER BY p.dueDate ASC
            """)
    List<OverdueItem> oldestOverdueForAgent(@Param("org") UUID organizationId, @Param("agent") UUID agentId,
                                            @Param("today") LocalDate today, Pageable limit);

    @Query("""
            SELECT COUNT(r) FROM Reminder r
            WHERE r.organizationId = :org
              AND r.status = com.policypulse.common.Domain$ReminderStatus.PENDING
            """)
    long pendingRemindersForOrganization(@Param("org") UUID organizationId);

    /** Reminders carry no agent, so an agent's share is found through the policy. */
    @Query("""
            SELECT COUNT(r) FROM Reminder r JOIN Policy pol ON pol.id = r.policyId
            WHERE r.organizationId = :org AND pol.agentId = :agent
              AND r.status = com.policypulse.common.Domain$ReminderStatus.PENDING
            """)
    long pendingRemindersForAgent(@Param("org") UUID organizationId, @Param("agent") UUID agentId);

    @Query("""
            SELECT COUNT(f) FROM FollowUp f
            WHERE f.organizationId = :org
              AND f.status IN (com.policypulse.common.Domain$FollowUpStatus.OPEN,
                               com.policypulse.common.Domain$FollowUpStatus.DUE)
              AND f.dueAt < :now
            """)
    long followUpsDueForOrganization(@Param("org") UUID organizationId, @Param("now") Instant now);

    @Query("""
            SELECT COUNT(f) FROM FollowUp f
            WHERE f.organizationId = :org AND f.assignedAgentId = :agent
              AND f.status IN (com.policypulse.common.Domain$FollowUpStatus.OPEN,
                               com.policypulse.common.Domain$FollowUpStatus.DUE)
              AND f.dueAt < :now
            """)
    long followUpsDueForAgent(@Param("org") UUID organizationId, @Param("agent") UUID agentId,
                              @Param("now") Instant now);

    @Query("""
            SELECT COUNT(c) FROM Customer c
            WHERE c.organizationId = :org
              AND c.status = com.policypulse.common.Domain$EntityStatus.ACTIVE
            """)
    long activeCustomersForOrganization(@Param("org") UUID organizationId);

    @Query("""
            SELECT COUNT(c) FROM Customer c
            WHERE c.organizationId = :org AND c.assignedAgentId = :agent
              AND c.status = com.policypulse.common.Domain$EntityStatus.ACTIVE
            """)
    long activeCustomersForAgent(@Param("org") UUID organizationId, @Param("agent") UUID agentId);

    @Query("""
            SELECT COUNT(pol) FROM Policy pol
            WHERE pol.organizationId = :org
              AND pol.status = com.policypulse.common.Domain$PolicyStatus.ACTIVE
            """)
    long activePoliciesForOrganization(@Param("org") UUID organizationId);

    @Query("""
            SELECT COUNT(pol) FROM Policy pol
            WHERE pol.organizationId = :org AND pol.agentId = :agent
              AND pol.status = com.policypulse.common.Domain$PolicyStatus.ACTIVE
            """)
    long activePoliciesForAgent(@Param("org") UUID organizationId, @Param("agent") UUID agentId);
}
