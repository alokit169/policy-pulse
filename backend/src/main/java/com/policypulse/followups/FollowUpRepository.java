package com.policypulse.followups;

import com.policypulse.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowUpRepository extends JpaRepository<FollowUp, UUID> {

    Optional<FollowUp> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<FollowUp> findByOrganizationIdOrderByDueAtAsc(UUID organizationId, Pageable pageable);

    Page<FollowUp> findByOrganizationIdAndStatusOrderByDueAtAsc(
            UUID organizationId, Domain.FollowUpStatus status, Pageable pageable);

    Page<FollowUp> findByOrganizationIdAndAssignedAgentIdOrderByDueAtAsc(
            UUID organizationId, UUID assignedAgentId, Pageable pageable);

    Page<FollowUp> findByOrganizationIdAndAssignedAgentIdAndStatusOrderByDueAtAsc(
            UUID organizationId, UUID assignedAgentId, Domain.FollowUpStatus status, Pageable pageable);

    List<FollowUp> findByCustomerIdOrderByDueAtAsc(UUID customerId);

    /**
     * One tenant's outstanding follow-ups whose moment has passed, oldest first.
     * Bounded, so a tenant that has let a year of work pile up cannot make one
     * sweep try to load all of it.
     */
    List<FollowUp> findTop200ByOrganizationIdAndStatusInAndDueAtLessThanEqualOrderByDueAtAsc(
            UUID organizationId, List<Domain.FollowUpStatus> statuses, Instant dueAt);

    /** Outstanding work already past its moment, for the dashboard. */
    long countByOrganizationIdAndStatusInAndDueAtBefore(
            UUID organizationId, List<Domain.FollowUpStatus> statuses, Instant before);
}
