package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.math.BigDecimal;

@Service
public class ProductsService {
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999999999999.99");
    private final ProductsRepository productRepository;

    public ProductsService(ProductsRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<Products> getAllProducts() {
        return productRepository.findAll();
    }

    public List<Products> getAllProducts(String q, String category, String minPrice, String maxPrice) {
        String name = trimFilter(q);
        String productCategory = trimFilter(category);
        BigDecimal minimum = parsePrice(minPrice, "minPrice");
        BigDecimal maximum = parsePrice(maxPrice, "maxPrice");
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("minPrice deve essere minore o uguale a maxPrice");
        }
        if (name == null && productCategory == null && minimum == null && maximum == null) {
            return getAllProducts();
        }
        return productRepository.findFiltered(name, productCategory, minimum, maximum);
    }

    private static String trimFilter(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static BigDecimal parsePrice(String value, String parameter) {
        String trimmed = trimFilter(value);
        if (trimmed == null) return null;
        if (!trimmed.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException(parameter + ": prezzo non valido");
        }
        BigDecimal price = new BigDecimal(trimmed);
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new IllegalArgumentException(parameter + ": prezzo fuori intervallo");
        }
        return price;
    }

    public Products getById(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }
}
