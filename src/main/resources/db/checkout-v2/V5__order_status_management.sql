-- Experimental data policy: all existing orders are considered already deducted.
-- Existing order states and history are left unchanged.
ALTER TABLE orders ADD COLUMN stock_deducted BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE order_status_changes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id_order BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    previous_status VARCHAR(32) NOT NULL,
    new_status VARCHAR(32) NOT NULL,
    CONSTRAINT fk_order_status_changes_order FOREIGN KEY (id_order) REFERENCES orders(id)
);
CREATE INDEX ix_order_status_changes_order ON order_status_changes(id_order);
