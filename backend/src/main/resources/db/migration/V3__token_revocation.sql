-- Phase 2 made a deactivated account's token stop working, but there was no way
-- to revoke a token that had been stolen from an account that stays active.
--
-- The version is carried as a claim in every token and compared on each request,
-- so raising it invalidates every token already issued to that user. Password
-- changes and an explicit "sign out everywhere" both raise it.
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
