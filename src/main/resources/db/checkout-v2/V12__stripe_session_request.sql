ALTER TABLE payment_attempts ADD COLUMN session_request TEXT NULL;
ALTER TABLE payment_attempts ADD COLUMN session_started_at DATETIME(6) NULL;
ALTER TABLE payment_attempts ADD CONSTRAINT ck_session_request_pair CHECK (
    (session_request IS NULL AND session_started_at IS NULL) OR
    (session_request IS NOT NULL AND session_started_at IS NOT NULL));
