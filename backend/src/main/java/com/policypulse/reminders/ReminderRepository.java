package com.policypulse.reminders;

import com.policypulse.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {
    boolean existsByIdempotencyKey(String key);
    Page<Reminder> findByOrganizationId(UUID orgId, Pageable pageable);
    Page<Reminder> findByOrganizationIdAndStatus(UUID orgId, Domain.ReminderStatus status, Pageable pageable);

    /**
     * Takes a reminder for delivery, and says whether it was this caller who got
     * it: one row changed for the winner, none for anybody else.
     *
     * <p>Reading the status and then writing it is not enough. Two sweeps can
     * both read PENDING and both go on to ring the customer, and by the time the
     * second one notices, the call has already been placed. A conditional update
     * settles it in the database, where the row lock decides.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reminder r
            SET r.status = com.policypulse.common.Domain$ReminderStatus.IN_PROGRESS
            WHERE r.id = :id
              AND r.status = com.policypulse.common.Domain$ReminderStatus.PENDING
            """)
    int claimForDelivery(@Param("id") UUID id);

    /**
     * Reminders now due to go out, including retries.
     *
     * <p>A first attempt is due at its scheduled moment; a later one at the
     * retry moment set after the last failure. Ordering by whichever applies
     * keeps the oldest work first.
     *
     * <p>Restricted to channels that can actually be delivered. A backlog on a
     * channel with no provider would otherwise sit at the head of this queue for
     * ever — oldest first, and never getting any younger — and, once it filled
     * the batch, starve every other tenant's reminders behind it.
     */
    @Query("""
            SELECT r FROM Reminder r
            WHERE r.status = com.policypulse.common.Domain$ReminderStatus.PENDING
              AND r.channel IN :channels
              AND COALESCE(r.nextAttemptAt, r.scheduledAt) <= :now
            ORDER BY COALESCE(r.nextAttemptAt, r.scheduledAt) ASC
            """)
    List<Reminder> findDue(@Param("channels") Collection<Domain.Channel> channels,
                           @Param("now") Instant now, Pageable limit);

    @Query("""
            SELECT r FROM Reminder r
            WHERE r.organizationId = :org
              AND r.status = com.policypulse.common.Domain$ReminderStatus.PENDING
              AND r.channel IN :channels
              AND COALESCE(r.nextAttemptAt, r.scheduledAt) <= :now
            ORDER BY COALESCE(r.nextAttemptAt, r.scheduledAt) ASC
            """)
    List<Reminder> findDueForOrganization(@Param("org") UUID organizationId,
                                          @Param("channels") Collection<Domain.Channel> channels,
                                          @Param("now") Instant now, Pageable limit);

    /**
     * Wakes the reminders that were put off because the calling window was shut,
     * for a tenant that has just changed that window.
     *
     * <p>Without this, widening a window does nothing until tomorrow: each
     * parked reminder was told when to come back based on the window as it was,
     * and would keep sleeping through the hours that have just been opened.
     *
     * <p>Only reminders that have never been dialled. A reminder with an attempt
     * behind it is waiting out a retry delay, which is about the call that
     * failed rather than about the window, so it keeps its moment.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Reminder r
            SET r.nextAttemptAt = NULL
            WHERE r.organizationId = :org
              AND r.status = com.policypulse.common.Domain$ReminderStatus.PENDING
              AND r.attemptCount = 0
              AND r.nextAttemptAt IS NOT NULL
            """)
    int wakeRemindersWaitingOnTheCallingWindow(@Param("org") UUID organizationId);

    /** How much is waiting on a channel nothing can deliver yet. */
    long countByStatusAndChannelNotIn(Domain.ReminderStatus status, Collection<Domain.Channel> channels);

    List<Reminder> findByCustomerIdOrderByScheduledAtDesc(UUID customerId);
    Optional<Reminder> findByIdAndOrganizationId(UUID id, UUID orgId);
}
