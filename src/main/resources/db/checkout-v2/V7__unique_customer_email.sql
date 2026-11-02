-- Derived value only: existing email values and accounts are never rewritten.
-- Duplicate normalized emails make CREATE UNIQUE INDEX fail; resolve explicitly beforehand.
ALTER TABLE customers ADD COLUMN email_normalized VARCHAR(255)
    GENERATED ALWAYS AS (LOWER(TRIM(email)));
CREATE UNIQUE INDEX uq_customers_email_normalized ON customers(email_normalized);
