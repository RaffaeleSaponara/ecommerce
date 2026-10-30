package com.TreDL.ecommerce.model;

import jakarta.persistence.*;

@Entity
@Table(name = "product_images")
public class ProductImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Products product;
    @Column(name = "storage_key", nullable = false, unique = true, length = 40)
    private String storageKey;
    protected ProductImage() {}
    public ProductImage(Products product, String storageKey) {
        this.product = product;
        this.storageKey = storageKey;
    }
    public Long getId() { return id; }
    public String getStorageKey() { return storageKey; }
    public String getUrl() { return "/media/products/" + storageKey; }
}
