package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.service.ProductImagesService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ProductDetailPageController {
    private final ProductImagesService images;
    public ProductDetailPageController(ProductImagesService images) { this.images = images; }

    @GetMapping("/prodotti/{id}")
    public String detail(@PathVariable long id, Model model, CsrfToken csrf) {
        csrf.getToken();
        model.addAttribute("detail", images.detail(id));
        return "prodotto";
    }
}
