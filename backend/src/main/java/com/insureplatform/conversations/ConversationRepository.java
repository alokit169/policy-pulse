package com.insureplatform.conversations;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Optional<Conversation> findByIdAndOrganizationId(UUID id, UUID orgId);
    Page<Conversation> findByOrganizationId(UUID orgId, Pageable pageable);
    List<Conversation> findByCustomerIdOrderByStartedAtDesc(UUID customerId);
    long countByOrganizationIdAndStartedAtAfter(UUID orgId, Instant after);
}
