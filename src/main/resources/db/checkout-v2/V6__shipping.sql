CREATE TABLE shipping_settings (
 id BIGINT NOT NULL PRIMARY KEY,
 cost DECIMAL(19,2) NOT NULL,
 free_threshold DECIMAL(19,2) NOT NULL,
 CONSTRAINT ck_shipping_cost CHECK(cost>=0), CONSTRAINT ck_shipping_threshold CHECK(free_threshold>=0)
);
INSERT INTO shipping_settings(id,cost,free_threshold) VALUES(1,5.90,79.00);
ALTER TABLE orders ADD COLUMN subtotal DECIMAL(19,2) NULL;
ALTER TABLE orders ADD COLUMN shipping_cost DECIMAL(19,2) NULL;
ALTER TABLE orders ADD COLUMN carrier VARCHAR(100) NULL;
ALTER TABLE orders ADD COLUMN tracking_code VARCHAR(150) NULL;
UPDATE orders SET subtotal=total,shipping_cost=0.00;
