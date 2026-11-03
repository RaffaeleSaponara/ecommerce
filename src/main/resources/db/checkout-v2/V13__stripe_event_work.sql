ALTER TABLE payment_events MODIFY COLUMN attempt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin;
ALTER TABLE payment_events ADD COLUMN session_ref VARCHAR(255) NULL;
ALTER TABLE payment_events ADD COLUMN attempt_hint VARCHAR(36) NULL;
ALTER TABLE payment_events ADD COLUMN order_hint BIGINT NULL;
ALTER TABLE payment_events ADD COLUMN next_attempt_at DATETIME(6) NULL;
ALTER TABLE payment_events ADD COLUMN lease_token VARCHAR(36) NULL;
ALTER TABLE payment_events ADD COLUMN lease_until DATETIME(6) NULL;
ALTER TABLE payment_events ADD COLUMN retries INT NOT NULL DEFAULT 0;
CREATE INDEX ix_event_pending ON payment_events(processed_at,next_attempt_at,lease_until);
