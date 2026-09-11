-- Locking an account after repeated failures.
--
-- Rate limiting is per client IP, which does nothing about a slow guessing
-- attack spread across addresses at one account. These two columns bound the
-- number of guesses that account will ever answer.
ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;
