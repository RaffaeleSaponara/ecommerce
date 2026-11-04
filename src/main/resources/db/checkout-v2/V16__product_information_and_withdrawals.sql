CREATE TABLE product_information (
 product_id BIGINT NOT NULL PRIMARY KEY,
 model_code VARCHAR(80) NULL,
 identification VARCHAR(255) NULL,
 materials VARCHAR(1000) NULL,
 weight_grams DECIMAL(9,3) NULL,
 dimensions VARCHAR(255) NULL,
 manufacturer_name VARCHAR(150) NULL,
 manufacturer_address VARCHAR(500) NULL,
 manufacturer_email VARCHAR(254) NULL,
 warnings VARCHAR(2000) NULL,
 instructions VARCHAR(2000) NULL,
 verified BOOLEAN NOT NULL DEFAULT FALSE,
 CONSTRAINT fk_information_product FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE,
 CONSTRAINT ck_information_weight CHECK(weight_grams IS NULL OR weight_grams>0)
);
CREATE TABLE withdrawal_requests (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 recipient_name VARCHAR(150) NOT NULL,
 receipt_email VARCHAR(254) NOT NULL,
 submitted_at DATETIME(6) NOT NULL,
 declaration TEXT NOT NULL,
 CONSTRAINT uq_withdrawal_order UNIQUE(order_id),
 CONSTRAINT fk_withdrawal_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE
);
ALTER TABLE email_notifications DROP CONSTRAINT ck_email_kind;
ALTER TABLE email_notifications ADD CONSTRAINT ck_email_kind CHECK(event_kind IN ('CONFIRMED','CANCELLED','SHIPPED','WITHDRAWAL'));
