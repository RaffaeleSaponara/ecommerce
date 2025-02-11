package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.service.CartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/cart")
public class CartController {

    @Autowired
    private CartService cartService;

    @GetMapping("/{customerId}")
    public Cart getCart(@PathVariable Long customerId) {
        return cartService.getCart(customerId);
    }

    @PostMapping("/{customerId}/add")
    public Cart addProduct(@PathVariable Long customerId, @RequestBody Products product) {
        return cartService.addProductToCart(customerId, product);
    }

    @PostMapping("/{customerId}/remove")
    public Cart removeProduct(@PathVariable Long customerId, @RequestBody Products product) {
        return cartService.removeProductToCart(customerId, product);
    }

    @DeleteMapping("/{customerId}/clear")
    public void clearCart(@PathVariable Long customerId) {
        cartService.clearCart(customerId);
    }
}