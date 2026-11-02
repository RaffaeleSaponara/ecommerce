-- Preserve all historical users, passwords and roles, including administrators.
ALTER TABLE customers ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE customers ALTER COLUMN email_verified SET DEFAULT FALSE;
CREATE TABLE email_verification_tokens (
    id_customer BIGINT NOT NULL PRIMARY KEY,
    token_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_email_verification_hash UNIQUE (token_hash),
    CONSTRAINT fk_email_verification_customer FOREIGN KEY (id_customer) REFERENCES customers(id) ON DELETE CASCADE
);
