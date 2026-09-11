package com.policypulse.reminders;

import com.policypulse.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {
    boolean existsByIdempotencyKey(String key);
    Page<Reminder> findByOrganizationId(UUID orgId, Pageable pageable);
    List<Reminder> findByStatusAndScheduledAtBefore(Domain.ReminderStatus status, Instant when);
    List<Reminder> findByStatusAndNextAttemptAtBefore(Domain.ReminderStatus status, Instant when);
    List<Reminder> findByCustomerIdOrderByScheduledAtDesc(UUID customerId);
    Optional<Reminder> findByIdAndOrganizationId(UUID id, UUID orgId);
}
