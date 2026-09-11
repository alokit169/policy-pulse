package com.policypulse.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByOrganizationIdOrderByTimestampDesc(UUID organizationId, Pageable pageable);

    List<AuditLog> findByActorEmailIgnoreCaseOrderByTimestampDesc(String actorEmail);
}
