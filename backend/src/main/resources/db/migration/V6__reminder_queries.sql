-- Reminders are listed per tenant, newest first. The existing index covers the
-- scheduler's (status, scheduled_at) lookup but not a tenant's own list.
CREATE INDEX ix_reminders_org_scheduled ON reminders (organization_id, scheduled_at DESC);

-- Detection asks for the instalments of one tenant falling due on one date.
CREATE INDEX ix_premium_payments_org_due ON premium_payments (organization_id, due_date);

-- Unread counts are read on every page load.
CREATE INDEX ix_notifications_user_created ON in_app_notifications (user_id, created_at DESC);
