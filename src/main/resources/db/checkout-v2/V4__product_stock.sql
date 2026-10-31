-- Unknown inventory is not sellable until explicitly counted by an administrator.
ALTER TABLE products ADD COLUMN stock INT NOT NULL DEFAULT 0;
ALTER TABLE products ADD CONSTRAINT ck_products_stock CHECK (stock >= 0);
