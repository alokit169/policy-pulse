package com.policypulse.premiums;

import com.policypulse.common.Domain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PremiumPaymentRepository extends JpaRepository<PremiumPayment, UUID> {
    List<PremiumPayment> findByPolicyIdOrderByDueDateDesc(UUID policyId);

    /** Schedules read oldest-first; covered by ix_premium_payments_policy_due. */
    List<PremiumPayment> findByPolicyIdOrderByDueDateAsc(UUID policyId);
    List<PremiumPayment> findByOrganizationIdAndStatusIn(UUID orgId, List<Domain.PremiumStatus> statuses);
    List<PremiumPayment> findByOrganizationIdAndStatusAndDueDateBetween(
            UUID orgId, Domain.PremiumStatus status, LocalDate from, LocalDate to);
    long countByOrganizationIdAndStatus(UUID orgId, Domain.PremiumStatus status);
    long countByOrganizationIdAndStatusAndDueDateBetween(UUID orgId, Domain.PremiumStatus status, LocalDate from, LocalDate to);
    Optional<PremiumPayment> findByIdAndOrganizationId(UUID id, UUID orgId);

    /** Reminder detection: one tenant's instalments falling due on one date. */
    List<PremiumPayment> findByOrganizationIdAndDueDateAndStatusIn(
            UUID organizationId, LocalDate dueDate, java.util.Collection<Domain.PremiumStatus> statuses);
    List<PremiumPayment> findByOrganizationIdAndVerificationPendingTrue(UUID orgId);

    /**
     * Whether anything on this policy that was owed by a given day is still
     * owed. Asked by date rather than by the stored status: an instalment's
     * status is written when it is created and only changed by a payment or a
     * waiver, so one raised as UPCOMING is still marked UPCOMING long after its
     * date has passed. The date is the fact; the status is a label.
     */
    @Query("""
            SELECT COUNT(p) FROM PremiumPayment p
            WHERE p.policyId = :policy
              AND p.dueDate <= :on
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.PAID
              AND p.status <> com.policypulse.common.Domain$PremiumStatus.WAIVED
            """)
    long countOwedOnOrBefore(@Param("policy") UUID policyId, @Param("on") LocalDate on);

    /**
     * Whether there was ever anything to pay by that day. Nothing owed means
     * nothing was paid when there was never an instalment in the first place, and
     * a policy with no schedule would otherwise look settled.
     */
    @Query("""
            SELECT COUNT(p) FROM PremiumPayment p
            WHERE p.policyId = :policy AND p.dueDate <= :on
            """)
    long countDueOnOrBefore(@Param("policy") UUID policyId, @Param("on") LocalDate on);

    /** Somebody is already checking a claimed payment against the books. */
    long countByPolicyIdAndVerificationPendingTrue(UUID policyId);
}
