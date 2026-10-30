package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductImagesService {
    public static final String PLACEHOLDER = "/images/product-placeholder.svg";
    private static final Logger log = LoggerFactory.getLogger(ProductImagesService.class);
    private final ProductsRepository products;
    private final ProductImagesRepository images;
    private final ProductImageStorage storage;

    public ProductImagesService(ProductsRepository products, ProductImagesRepository images, ProductImageStorage storage) {
        this.products = products;
        this.images = images;
        this.storage = storage;
    }

    public ProductDetailDTO detail(long productId) {
        Products product = product(productId, false);
        ProductDTO dto = ProductDTO.convertToDTO(product);
        String image = dto.getImage();
        // Legacy images are local static paths only; never return arbitrary schemes or traversal.
        if (image == null || !(image.matches("/images/[a-zA-Z0-9_/-]+\\.(?i:png|jpe?g|gif|webp|avif)")
                || image.equals(PLACEHOLDER)
                || image.matches("/media/products/[0-9a-f-]{36}\\.png"))) dto.setImage(PLACEHOLDER);
        return new ProductDetailDTO(dto, listFor(product));
    }

    public List<ProductImageDTO> list(long productId) { return listFor(product(productId, false)); }

    private List<ProductImageDTO> listFor(Products product) {
        return images.findByProductIdOrderByIdAsc(product.getId()).stream().map(image -> dto(image, product)).toList();
    }

    @Transactional
    public ProductImageDTO upload(long productId, MultipartFile file) {
        Products product = product(productId, true);
        List<ProductImage> current = images.findByProductIdOrderByIdAsc(productId);
        if (current.size() >= 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Massimo 10 immagini per prodotto");
        String key = storage.store(file);
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) cleanup(key);
                }
            });
            ProductImage image = images.saveAndFlush(new ProductImage(product, key));
            if (current.isEmpty()) product.setImage(image.getUrl());
            return dto(image, product);
        } catch (RuntimeException failure) {
            cleanup(key);
            throw failure;
        }
    }

    @Transactional
    public ProductImageDTO primary(long productId, long imageId) {
        Products product = product(productId, true);
        ProductImage image = owned(productId, imageId);
        product.setImage(image.getUrl());
        return dto(image, product);
    }

    @Transactional
    public void delete(long productId, long imageId) {
        Products product = product(productId, true);
        List<ProductImage> current = images.findByProductIdOrderByIdAsc(productId);
        ProductImage removed = current.stream().filter(image -> image.getId() == imageId).findFirst().orElseThrow(ProductImagesService::missingImage);
        images.delete(removed);
        if (removed.getUrl().equals(product.getImage())) product.setImage(current.stream()
                .filter(image -> image.getId() != imageId).findFirst().map(ProductImage::getUrl).orElse(PLACEHOLDER));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cleanup(removed.getStorageKey()); }
        });
    }

    public byte[] content(String key) {
        if (!images.existsByStorageKey(key)) throw missingImage();
        return storage.read(key);
    }

    private Products product(long id, boolean lock) {
        return (lock ? products.findByIdForUpdate(id) : products.findById(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Prodotto non trovato"));
    }

    private ProductImage owned(long productId, long imageId) {
        return images.findByProductIdOrderByIdAsc(productId).stream().filter(image -> image.getId() == imageId)
                .findFirst().orElseThrow(ProductImagesService::missingImage);
    }

    private ProductImageDTO dto(ProductImage image, Products product) {
        return new ProductImageDTO(image.getId(), image.getUrl(), image.getUrl().equals(product.getImage()));
    }

    private void cleanup(String key) {
        try { storage.delete(key); }
        catch (RuntimeException error) { log.error("Unable to remove product image {}", key, error); }
    }

    private static ResponseStatusException missingImage() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Immagine non trovata");
    }
}
