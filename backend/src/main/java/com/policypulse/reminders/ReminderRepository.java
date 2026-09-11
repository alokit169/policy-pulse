package com.policypulse.reminders;

import com.policypulse.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {
    boolean existsByIdempotencyKey(String key);
    Page<Reminder> findByOrganizationId(UUID orgId, Pageable pageable);
    Page<Reminder> findByOrganizationIdAndStatus(UUID orgId, Domain.ReminderStatus status, Pageable pageable);
    List<Reminder> findByStatusAndScheduledAtBefore(Domain.ReminderStatus status, Instant when);

    /**
     * Reminders now due to go out, including retries.
     *
     * <p>A first attempt is due at its scheduled moment; a later one at the
     * retry moment set after the last failure. Ordering by whichever applies
     * keeps the oldest work first.
     */
    @Query("""
            SELECT r FROM Reminder r
            WHERE r.status = com.policypulse.common.Domain$ReminderStatus.PENDING
              AND COALESCE(r.nextAttemptAt, r.scheduledAt) <= :now
            ORDER BY COALESCE(r.nextAttemptAt, r.scheduledAt) ASC
            """)
    List<Reminder> findDue(@Param("now") Instant now, Pageable limit);

    @Query("""
            SELECT r FROM Reminder r
            WHERE r.organizationId = :org
              AND r.status = com.policypulse.common.Domain$ReminderStatus.PENDING
              AND COALESCE(r.nextAttemptAt, r.scheduledAt) <= :now
            ORDER BY COALESCE(r.nextAttemptAt, r.scheduledAt) ASC
            """)
    List<Reminder> findDueForOrganization(@Param("org") UUID organizationId,
                                          @Param("now") Instant now, Pageable limit);

    /** Bounded, so one sweep cannot try to load every overdue reminder at once. */
    List<Reminder> findTop200ByStatusAndScheduledAtBeforeOrderByScheduledAtAsc(
            Domain.ReminderStatus status, Instant when);

    /** The same, for one tenant, used by the on-demand run. */
    List<Reminder> findTop200ByOrganizationIdAndStatusAndScheduledAtBeforeOrderByScheduledAtAsc(
            UUID organizationId, Domain.ReminderStatus status, Instant when);
    List<Reminder> findByStatusAndNextAttemptAtBefore(Domain.ReminderStatus status, Instant when);
    List<Reminder> findByCustomerIdOrderByScheduledAtDesc(UUID customerId);
    Optional<Reminder> findByIdAndOrganizationId(UUID id, UUID orgId);
}
