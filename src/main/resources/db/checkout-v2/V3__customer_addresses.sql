-- Mutable address book; order snapshots have no foreign key to it.
CREATE TABLE customer_addresses (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id_customer BIGINT NOT NULL,
    recipient_name VARCHAR(150) NOT NULL,
    street VARCHAR(180) NOT NULL,
    house_number VARCHAR(20) NOT NULL,
    postal_code VARCHAR(5) NOT NULL,
    municipality VARCHAR(100) NOT NULL,
    province VARCHAR(2) NOT NULL,
    country VARCHAR(2) NOT NULL,
    delivery_notes VARCHAR(500),
    default_address BOOLEAN NOT NULL,
    CONSTRAINT fk_addresses_customer FOREIGN KEY (id_customer) REFERENCES customers(id)
);
CREATE INDEX ix_addresses_customer ON customer_addresses(id_customer);
ALTER TABLE orders ADD COLUMN shipping_recipient_name VARCHAR(150);
ALTER TABLE orders ADD COLUMN shipping_street VARCHAR(180);
ALTER TABLE orders ADD COLUMN shipping_house_number VARCHAR(20);
ALTER TABLE orders ADD COLUMN shipping_postal_code VARCHAR(5);
ALTER TABLE orders ADD COLUMN shipping_municipality VARCHAR(100);
ALTER TABLE orders ADD COLUMN shipping_province VARCHAR(2);
ALTER TABLE orders ADD COLUMN shipping_country VARCHAR(2);
ALTER TABLE orders ADD COLUMN shipping_delivery_notes VARCHAR(500);
ALTER TABLE orders ADD COLUMN billing_recipient_name VARCHAR(150);
ALTER TABLE orders ADD COLUMN billing_street VARCHAR(180);
ALTER TABLE orders ADD COLUMN billing_house_number VARCHAR(20);
ALTER TABLE orders ADD COLUMN billing_postal_code VARCHAR(5);
ALTER TABLE orders ADD COLUMN billing_municipality VARCHAR(100);
ALTER TABLE orders ADD COLUMN billing_province VARCHAR(2);
ALTER TABLE orders ADD COLUMN billing_country VARCHAR(2);
ALTER TABLE orders ADD COLUMN billing_delivery_notes VARCHAR(500);
