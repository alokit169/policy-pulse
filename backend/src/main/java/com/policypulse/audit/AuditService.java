package com.policypulse.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes in its own transaction. A failed login records the attempt and then
     * throws; without a separate transaction that rollback would discard the very
     * record the audit trail exists to keep.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entity, String entityId,
                       UUID organizationId, UUID actorId, String actorEmail, String metadata) {
        repository.save(new AuditLog(
                organizationId, actorId, actorEmail, action.name(), entity, entityId, metadata));
    }
}
