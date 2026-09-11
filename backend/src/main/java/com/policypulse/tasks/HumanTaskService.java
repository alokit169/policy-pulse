package com.policypulse.tasks;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class HumanTaskService {
    private static final String ENTITY = "HumanTask";

    private static final List<Domain.HumanTaskStatus> OUTSTANDING =
            List.of(Domain.HumanTaskStatus.OPEN, Domain.HumanTaskStatus.IN_PROGRESS);

    private final HumanTaskRepository tasks;
    private final AuditService audit;

    public HumanTaskService(HumanTaskRepository tasks, AuditService audit) {
        this.tasks = tasks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<HumanTaskResponse> search(Domain.HumanTaskStatus status, Pageable pageable) {
        AuthUser caller = SecurityUtil.current();
        UUID orgId = caller.getOrganizationId();
        boolean ownOnly = caller.role() == Domain.Role.AGENT;

        Page<HumanTask> page;
        if (ownOnly) {
            page = status == null
                    ? tasks.findByOrganizationIdAndAssignedAgentIdOrderByCreatedAtDesc(orgId, caller.getId(), pageable)
                    : tasks.findByOrganizationIdAndAssignedAgentIdAndStatusOrderByCreatedAtDesc(
                            orgId, caller.getId(), status, pageable);
        } else {
            page = status == null
                    ? tasks.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable)
                    : tasks.findByOrganizationIdAndStatusOrderByCreatedAtDesc(orgId, status, pageable);
        }

        return new PageResponse<>(
                page.getContent().stream().map(HumanTaskResponse::of).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize());
    }

    @Transactional
    public HumanTaskResponse complete(UUID id) {
        return settle(id, Domain.HumanTaskStatus.COMPLETED);
    }

    @Transactional
    public HumanTaskResponse cancel(UUID id) {
        return settle(id, Domain.HumanTaskStatus.CANCELLED);
    }

    private HumanTaskResponse settle(UUID id, Domain.HumanTaskStatus status) {
        AuthUser caller = SecurityUtil.current();
        HumanTask task = tasks.findByIdAndOrganizationId(id, caller.getOrganizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task not found"));

        if (caller.role() == Domain.Role.AGENT && !caller.getId().equals(task.getAssignedAgentId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Task not found");
        }
        if (!OUTSTANDING.contains(task.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "This task is already closed");
        }

        task.setStatus(status);
        tasks.save(task);

        audit.record(AuditAction.HUMAN_TASK_CLOSED, ENTITY, task.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), status.name());
        return HumanTaskResponse.of(task);
    }
}
