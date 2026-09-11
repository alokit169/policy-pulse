package com.policypulse.conversations;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {
    List<ConversationMessage> findByConversationIdOrderByTimestampAsc(UUID conversationId);
}
