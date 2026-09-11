-- Recording a payment reads an instalment, checks it is not already paid, then
-- writes. Nothing stopped two concurrent requests from both passing that check
-- and both writing, leaving one payment reference silently overwritten by the
-- other and two audit entries for a single collection.
--
-- A version column makes the second write fail instead, so the caller is told to
-- retry rather than quietly losing a record of money received.
ALTER TABLE premium_payments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
