package com.policypulse.policies;

import com.policypulse.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
    Optional<Policy> findByIdAndOrganizationId(UUID id, UUID orgId);
    List<Policy> findByCustomerIdAndOrganizationId(UUID customerId, UUID orgId);
    Page<Policy> findByOrganizationId(UUID orgId, Pageable pageable);
    Page<Policy> findByOrganizationIdAndAgentId(UUID orgId, UUID agentId, Pageable pageable);
    Page<Policy> findByOrganizationIdAndStatus(UUID orgId, Domain.PolicyStatus status, Pageable pageable);
    Page<Policy> findByOrganizationIdAndAgentIdAndStatus(
            UUID orgId, UUID agentId, Domain.PolicyStatus status, Pageable pageable);
    List<Policy> findByOrganizationIdAndStatusAndNextPremiumDueDateBetween(
            UUID orgId, Domain.PolicyStatus status, LocalDate from, LocalDate to);
    List<Policy> findByOrganizationIdAndStatusAndMaturityDateBetween(
            UUID orgId, Domain.PolicyStatus status, LocalDate from, LocalDate to);
    List<Policy> findByOrganizationIdAndStatus(UUID orgId, Domain.PolicyStatus status);
    long countByOrganizationIdAndStatus(UUID orgId, Domain.PolicyStatus status);
    long countByOrganizationIdAndAgentIdAndStatus(UUID orgId, UUID agentId, Domain.PolicyStatus status);
    Optional<Policy> findByOrganizationIdAndPolicyNumber(UUID orgId, String policyNumber);
}
