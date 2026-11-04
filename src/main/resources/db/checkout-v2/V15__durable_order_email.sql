ALTER TABLE orders ADD COLUMN contract_version VARCHAR(80) NULL;
ALTER TABLE orders ADD COLUMN contract_captured_at DATETIME(6) NULL;
ALTER TABLE orders ADD COLUMN contract_text TEXT NULL;
CREATE TABLE email_notifications (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 event_kind VARCHAR(20) NOT NULL,
 recipient VARCHAR(254) NOT NULL,
 subject VARCHAR(255) NOT NULL,
 text_body TEXT NOT NULL,
 html_body TEXT NOT NULL,
 contract_text TEXT NULL,
 created_at DATETIME(6) NOT NULL,
 next_attempt_at DATETIME(6) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 smtp_accepted_at DATETIME(6) NULL,
 last_error VARCHAR(40) NULL,
 CONSTRAINT uq_email_event UNIQUE(order_id,event_kind),
 CONSTRAINT fk_email_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE,
 CONSTRAINT ck_email_attempts CHECK(attempts >= 0),
 CONSTRAINT ck_email_kind CHECK(event_kind IN ('CONFIRMED','CANCELLED','SHIPPED'))
);
CREATE INDEX ix_email_work ON email_notifications(smtp_accepted_at,next_attempt_at,lease_until);
