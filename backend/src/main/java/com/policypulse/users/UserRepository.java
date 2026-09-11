package com.policypulse.users;

import com.policypulse.common.Domain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    List<AppUser> findByOrganizationId(UUID organizationId);
    List<AppUser> findByOrganizationIdAndRole(UUID organizationId, Domain.Role role);
    List<AppUser> findByManagerId(UUID managerId);

    /**
     * Counts one wrong password, in the database rather than in memory.
     *
     * <p>Reading the count, adding one and writing it back is not counting: ten
     * guesses arriving together all read zero and all write one, and the account
     * never reaches the limit at all. An attacker does not have to send guesses
     * one at a time. The row lock this update takes is what makes ten mean ten.
     *
     * <p>A lock that has run out resets the count instead of adding to it, so the
     * owner gets their attempts back rather than one every fifteen minutes for
     * the rest of the day.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AppUser u
            SET u.failedLoginAttempts = CASE
                    WHEN u.lockedUntil IS NOT NULL AND u.lockedUntil <= :now THEN 1
                    ELSE u.failedLoginAttempts + 1 END,
                u.lockedUntil = CASE
                    WHEN u.lockedUntil IS NOT NULL AND u.lockedUntil <= :now THEN NULL
                    ELSE u.lockedUntil END
            WHERE u.id = :id
            """)
    int countFailedLogin(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Locks an account that has now had too many, and says whether it was this
     * caller who locked it — so the audit records one lock rather than one per
     * guess that arrived at the same moment.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AppUser u
            SET u.lockedUntil = :until
            WHERE u.id = :id
              AND u.failedLoginAttempts >= :limit
              AND u.lockedUntil IS NULL
            """)
    int lockIfOverLimit(@Param("id") UUID id, @Param("limit") int limit,
                        @Param("until") Instant until);
}
