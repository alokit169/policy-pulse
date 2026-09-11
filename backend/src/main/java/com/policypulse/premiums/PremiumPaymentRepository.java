package com.policypulse.premiums;

import com.policypulse.common.Domain;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
