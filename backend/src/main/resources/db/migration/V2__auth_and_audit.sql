-- Phase 2: authentication and audit.

-- V1 made email unique only within an organization, but login takes an email
-- and no tenant hint, so a duplicate across organizations would make the
-- account lookup ambiguous. One email therefore identifies exactly one user.
CREATE UNIQUE INDEX ux_users_email_lower ON users (LOWER(email));

-- Audit queries are always scoped to a tenant and read newest-first.
CREATE INDEX ix_audit_logs_org_timestamp ON audit_logs (organization_id, timestamp DESC);
CREATE INDEX ix_audit_logs_actor ON audit_logs (actor_id);
