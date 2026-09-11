package com.insureplatform.conversations;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_messages")
public class ConversationMessage {
    @Id
    private UUID id;
    private UUID conversationId;
    private String sender;
    @Column(columnDefinition = "TEXT")
    private String message;
    private Instant timestamp;
    private String transcriptReference;
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        if (timestamp == null) timestamp = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getTranscriptReference() { return transcriptReference; }
    public void setTranscriptReference(String transcriptReference) { this.transcriptReference = transcriptReference; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
