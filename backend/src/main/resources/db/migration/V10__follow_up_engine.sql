-- A broken promise is escalated once, not once an hour. Recording the moment is
-- the guard against raising the same task on every sweep, and it also answers
-- how long a commitment has been outstanding without reading the audit log.
ALTER TABLE follow_ups ADD COLUMN escalated_at TIMESTAMPTZ;

-- The engine asks one tenant for outstanding follow-ups whose moment has passed,
-- which is what idx_followups_due (organization_id, status, due_at) already
-- answers. No new index.
