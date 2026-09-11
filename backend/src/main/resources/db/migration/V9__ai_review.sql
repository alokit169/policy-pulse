-- Work raised for a person is read as a queue: the open ones, most recent first,
-- for one agent or for the whole tenant.
CREATE INDEX ix_human_tasks_org_status ON human_tasks (organization_id, status, created_at DESC);
CREATE INDEX ix_human_tasks_agent_status ON human_tasks (organization_id, assigned_agent_id, status);

-- A premium awaiting someone's confirmation is looked up on its own, and there
-- are few of them next to the whole schedule.
CREATE INDEX ix_premium_payments_verification
    ON premium_payments (organization_id) WHERE verification_pending;
