package com.policypulse.tasks;

import com.policypulse.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HumanTaskRepository extends JpaRepository<HumanTask, UUID> {

    Optional<HumanTask> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<HumanTask> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    Page<HumanTask> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, Domain.HumanTaskStatus status, Pageable pageable);

    Page<HumanTask> findByOrganizationIdAndAssignedAgentIdOrderByCreatedAtDesc(
            UUID organizationId, UUID assignedAgentId, Pageable pageable);

    Page<HumanTask> findByOrganizationIdAndAssignedAgentIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, UUID assignedAgentId, Domain.HumanTaskStatus status, Pageable pageable);

    List<HumanTask> findByConversationId(UUID conversationId);

    long countByOrganizationIdAndStatusIn(UUID organizationId, List<Domain.HumanTaskStatus> statuses);

    long countByOrganizationIdAndAssignedAgentIdAndStatusIn(
            UUID organizationId, UUID assignedAgentId, List<Domain.HumanTaskStatus> statuses);
}
