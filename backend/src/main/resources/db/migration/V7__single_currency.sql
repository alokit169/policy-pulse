-- The product deals in rupees only. Currency was per policy, which meant a
-- tenant could hold policies in different currencies and any total spanning them
-- would have been adding dollars to rupees.
--
-- Anything already entered in another currency is a data-entry mistake rather
-- than a real dollar policy, so the amounts are left alone and only the label is
-- corrected.
UPDATE policies SET currency_code = 'INR' WHERE currency_code <> 'INR';

-- Enforced by the database, not only by validation, so a sum across policies is
-- provably safe. Dropping this constraint is what a future multi-currency change
-- would begin with.
ALTER TABLE policies
    ADD CONSTRAINT ck_policies_currency_inr CHECK (currency_code = 'INR');
