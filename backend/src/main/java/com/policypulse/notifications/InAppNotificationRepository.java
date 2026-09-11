package com.policypulse.notifications;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, UUID> {

    Page<InAppNotification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<InAppNotification> findByUserIdAndReadFalseOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadFalse(UUID userId);

    /** Scoped by user as well as id, so one user cannot read another's message. */
    Optional<InAppNotification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InAppNotification n SET n.read = true WHERE n.userId = :userId AND n.read = false")
    int markAllReadFor(@Param("userId") UUID userId);
}
