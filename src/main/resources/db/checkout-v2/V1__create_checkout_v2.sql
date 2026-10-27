-- Fresh empty databases only. Explicit Flyway location, never auto-discovered.
CREATE TABLE customers (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    role VARCHAR(255) NOT NULL,
    CONSTRAINT uq_customers_username UNIQUE (username)
);

CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(255) NOT NULL,
    prezzo DECIMAL(19,2) NOT NULL,
    descrizione VARCHAR(255),
    categoria VARCHAR(255),
    image VARCHAR(255),
    CONSTRAINT ck_products_price CHECK (prezzo >= 0)
);

CREATE TABLE cart (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id_customer BIGINT NOT NULL,
    CONSTRAINT uq_cart_customer UNIQUE (id_customer),
    CONSTRAINT fk_cart_customer FOREIGN KEY (id_customer) REFERENCES customers(id)
);

-- A bag: repeated (cart, product) rows represent quantity. No pair uniqueness.
CREATE TABLE cart_products (
    id_cart BIGINT NOT NULL,
    id_product BIGINT NOT NULL,
    CONSTRAINT fk_cart_products_cart FOREIGN KEY (id_cart) REFERENCES cart(id),
    CONSTRAINT fk_cart_products_product FOREIGN KEY (id_product) REFERENCES products(id)
);
CREATE INDEX ix_cart_products_cart ON cart_products(id_cart);
CREATE INDEX ix_cart_products_product ON cart_products(id_product);

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id_customer BIGINT NOT NULL,
    date DATETIME(6) NOT NULL,
    total DECIMAL(19,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'EUR',
    ship_status VARCHAR(255) NOT NULL,
    -- Byte comparison avoids MySQL collation folding distinct client keys.
    idempotency_key VARBINARY(128) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    CONSTRAINT fk_orders_customer FOREIGN KEY (id_customer) REFERENCES customers(id),
    CONSTRAINT uq_orders_customer_key UNIQUE (id_customer, idempotency_key),
    CONSTRAINT ck_orders_key CHECK (OCTET_LENGTH(idempotency_key) BETWEEN 1 AND 128),
    CONSTRAINT ck_orders_total CHECK (total >= 0),
    -- Character codes also enforce uppercase under case-insensitive collations.
    CONSTRAINT ck_orders_currency CHECK (
        ASCII(SUBSTRING(currency, 1, 1)) = ASCII('E') AND
        ASCII(SUBSTRING(currency, 2, 1)) = ASCII('U') AND
        ASCII(SUBSTRING(currency, 3, 1)) = ASCII('R')
    )
);

CREATE TABLE order_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    id_order BIGINT NOT NULL,
    id_product BIGINT,
    product_name VARCHAR(255) NOT NULL,
    unit_price DECIMAL(19,2) NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (id_order) REFERENCES orders(id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (id_product) REFERENCES products(id) ON DELETE SET NULL,
    CONSTRAINT ck_order_items_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_order_items_order ON order_items(id_order);
CREATE INDEX ix_order_items_product ON order_items(id_product);
