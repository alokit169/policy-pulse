package com.policypulse.followups;

import com.policypulse.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * One tenant's follow-ups past their moment that the engine can still do
     * something about, oldest first.
     *
     * <p>Deliberately not "everything outstanding". Once a follow-up has been
     * announced there is nothing further for the engine to do with it unless it
     * is a payment commitment, which can still be paid or still be broken; and
     * once that has been handed to a person, they own it. Keeping announced work
     * in this queue would mean an agency that lets its follow-ups pile up
     * eventually fills the batch with work nothing can act on, and never sees a
     * new one come due at all.
     */
    @Query("""
            SELECT f.id FROM FollowUp f
            WHERE f.organizationId = :org
              AND f.dueAt <= :now
              AND (f.status = com.policypulse.common.Domain$FollowUpStatus.OPEN
                   OR (f.status = com.policypulse.common.Domain$FollowUpStatus.DUE
                       AND f.escalatedAt IS NULL
                       AND f.reason = 'PAYMENT_COMMITMENT'))
            ORDER BY f.dueAt ASC
            """)
    List<UUID> findWorkableIds(@Param("org") UUID organizationId, @Param("now") Instant now, Pageable limit);

    /**
     * Announces a follow-up, and says whether it was this caller who did it.
     *
     * <p>The sweep runs hourly and a manager can run it by hand at the same
     * moment. Reading the status and then writing it lets both through, and the
     * agent is told twice about the same promise.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE FollowUp f
            SET f.status = com.policypulse.common.Domain$FollowUpStatus.DUE
            WHERE f.id = :id
              AND f.status = com.policypulse.common.Domain$FollowUpStatus.OPEN
            """)
    int bringDue(@Param("id") UUID id);

    /** Closes a follow-up nobody needs to chase. One caller wins. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE FollowUp f
            SET f.status = com.policypulse.common.Domain$FollowUpStatus.COMPLETED, f.notes = :notes
            WHERE f.id = :id
              AND f.status IN (com.policypulse.common.Domain$FollowUpStatus.OPEN,
                               com.policypulse.common.Domain$FollowUpStatus.DUE)
            """)
    int settle(@Param("id") UUID id, @Param("notes") String notes);

    /** Takes the right to hand this broken promise to a person. One caller wins. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE FollowUp f
            SET f.escalatedAt = :now
            WHERE f.id = :id
              AND f.escalatedAt IS NULL
            """)
    int claimEscalation(@Param("id") UUID id, @Param("now") Instant now);

    /** Outstanding work already past its moment, for the dashboard. */
    long countByOrganizationIdAndStatusInAndDueAtBefore(
            UUID organizationId, List<Domain.FollowUpStatus> statuses, Instant before);
}
