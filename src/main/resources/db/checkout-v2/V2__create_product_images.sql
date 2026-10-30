CREATE TABLE product_images (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    storage_key VARCHAR(40) NOT NULL,
    CONSTRAINT uq_product_images_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products(id)
);
CREATE INDEX ix_product_images_product ON product_images(product_id);
