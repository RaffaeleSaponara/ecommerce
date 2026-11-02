CREATE TABLE password_reset_tokens (
    id_customer BIGINT NOT NULL PRIMARY KEY,
    token_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_password_reset_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_customer FOREIGN KEY (id_customer) REFERENCES customers(id) ON DELETE CASCADE
);
