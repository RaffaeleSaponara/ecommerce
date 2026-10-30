package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.dto.ProductDTO;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.service.ProductsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = "/prodotti")
public class ProductsController {

    @Autowired
    private final ProductsService productsService;

    public ProductsController(ProductsService productsService) {
        this.productsService = productsService;
    }

    @GetMapping("/all")
    public List<ProductDTO> getFilteredProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String minPrice,
            @RequestParam(required = false) String maxPrice) {
        if (q == null && category == null && minPrice == null && maxPrice == null) {
            return getAllProducts();
        }
        return productsService.getAllProducts(q, category, minPrice, maxPrice).stream()
                .map(ProductDTO::convertToDTO).toList();
    }

    public List<ProductDTO> getAllProducts() {
        List<Products> allProducts = productsService.getAllProducts();
        List<ProductDTO> allProductsDTO = new ArrayList<>();
        for (Products p: allProducts){
            allProductsDTO.add(ProductDTO.convertToDTO(p));
        }
        return allProductsDTO;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidFilters(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
