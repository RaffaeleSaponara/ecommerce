package com.TreDL.ecommerce.repository;

import com.TreDL.ecommerce.model.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductImagesRepository extends JpaRepository<ProductImage, Long> {
    List<ProductImage> findByProductIdOrderByIdAsc(long productId);
    boolean existsByStorageKey(String storageKey);
}
