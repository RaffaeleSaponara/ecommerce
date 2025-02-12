package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductsService {
    private final ProductsRepository productRepository;

    public ProductsService(ProductsRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<Products> getAllProducts() {
        return productRepository.findAll();
    }
}
