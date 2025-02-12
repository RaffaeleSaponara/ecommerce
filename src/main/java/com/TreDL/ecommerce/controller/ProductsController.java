package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.dto.ProductDTO;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.service.ProductsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(value = "/prodotti")
public class ProductsController {

    @Autowired
    private final ProductsService productsService;

    public ProductsController(ProductsService productsService) {
        this.productsService = productsService;
    }

    @GetMapping("/all")
    public List<ProductDTO> getAllProducts() {
        List<Products> allProducts = productsService.getAllProducts();
        List<ProductDTO> allProductsDTO = new ArrayList<>();
        for (Products p: allProducts){
            allProductsDTO.add(ProductDTO.convertToDTO(p));
        }
        return allProductsDTO;
    }
}
