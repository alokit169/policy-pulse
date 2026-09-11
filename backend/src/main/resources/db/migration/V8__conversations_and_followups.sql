-- Conversations are listed per tenant, newest first, and a customer's history is
-- read from their record.
CREATE INDEX ix_conversations_org_started ON conversations (organization_id, started_at DESC);
CREATE INDEX ix_conversations_customer_started ON conversations (customer_id, started_at DESC);

-- An agent opens their own follow-ups far more often than the whole tenant's.
CREATE INDEX ix_follow_ups_agent_due ON follow_ups (organization_id, assigned_agent_id, due_at);
CREATE INDEX ix_follow_ups_customer ON follow_ups (customer_id);

-- A transcript is always read in order for one conversation.
CREATE INDEX ix_conversation_messages_conversation ON conversation_messages (conversation_id, timestamp);
