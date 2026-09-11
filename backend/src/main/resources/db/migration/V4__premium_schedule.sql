-- A policy's premium schedule is generated from its start date and frequency.
-- Regenerating it after the terms change must never produce a second instalment
-- for a date that already has one, so the database enforces it rather than
-- relying on the generator being careful.
ALTER TABLE premium_payments
    ADD CONSTRAINT ux_premium_payments_policy_due UNIQUE (policy_id, due_date);

-- Listing a policy's schedule reads oldest-first; the existing index covers the
-- policy but not the ordering.
CREATE INDEX ix_premium_payments_policy_due ON premium_payments (policy_id, due_date);
