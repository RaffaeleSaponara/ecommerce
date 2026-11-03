-- Historical orders remain legacy; no payment records are synthesized.
ALTER TABLE orders ADD COLUMN payment_flow VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE orders ADD CONSTRAINT ck_order_payment_flow CHECK (payment_flow IN ('LEGACY','STRIPE_TEST'));

CREATE TABLE payment_attempts (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    id_order BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_amount DECIMAL(19,2) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_session_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_payment_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    close_requested_at DATETIME(6) NULL,
    next_reconcile_at DATETIME(6) NULL,
    CONSTRAINT fk_payment_order FOREIGN KEY (id_order) REFERENCES orders(id),
    CONSTRAINT uq_payment_order UNIQUE (id_order),
    CONSTRAINT uq_payment_session UNIQUE (provider_session_id),
    CONSTRAINT uq_payment_reference UNIQUE (provider_payment_id),
    CONSTRAINT ck_payment_status CHECK (status IN ('CREATED','PENDING','SUCCEEDED','FAILED','EXPIRED','CANCELLED')),
    CONSTRAINT ck_payment_amount CHECK (expected_amount >= 0),
    CONSTRAINT ck_payment_currency CHECK (currency = 'EUR'),
    CONSTRAINT ck_payment_expiration CHECK (expires_at > created_at)
);
CREATE INDEX ix_payment_reconcile ON payment_attempts(status,next_reconcile_at);

CREATE TABLE payment_events (
    id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    outcome VARCHAR(32) NULL,
    CONSTRAINT fk_event_attempt FOREIGN KEY (attempt_id) REFERENCES payment_attempts(id),
    CONSTRAINT ck_event_processed CHECK (processed_at IS NULL OR processed_at >= received_at)
);
CREATE INDEX ix_event_attempt ON payment_events(attempt_id);

CREATE TABLE refunds (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_refund_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_refund_attempt FOREIGN KEY (attempt_id) REFERENCES payment_attempts(id),
    CONSTRAINT uq_refund_attempt UNIQUE (attempt_id),
    CONSTRAINT uq_refund_key UNIQUE (idempotency_key),
    CONSTRAINT uq_refund_provider UNIQUE (provider_refund_id),
    CONSTRAINT ck_refund_status CHECK (status IN ('REQUESTED','PENDING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_refund_currency CHECK (currency = 'EUR')
);
