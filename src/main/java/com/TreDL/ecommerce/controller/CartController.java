package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.service.CartService;
import com.TreDL.ecommerce.service.CustomersService;
import com.TreDL.ecommerce.service.ProductsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Optional;

@RestController
@RequestMapping("/cart")
public class CartController {

    @Autowired
    private CartService cartService;
    @Autowired
    private ProductsService productsService;
    @Autowired
    private CustomersService customersService;

//    @GetMapping("/{customerId}")
//    public Cart getCart(@PathVariable Long customerId) {
//        return cartService.getCart(customerId);
//    }

//    @PostMapping("/{customerId}/add")
//    public Cart addProduct(@PathVariable Long customerId, @RequestBody Products product) {
//        return cartService.addProductToCart(customerId, product);
//    }

    @PostMapping("/add")
    public Cart addProduct(@RequestBody Long id, Principal principal) {
        Customers customer= customersService.getByUsername(principal.getName());
        Products product = productsService.getById(id);
        return cartService.addProductToCart(customer, product);
    }

//    @PostMapping("/{customerId}/remove")
//    public Cart removeProduct(@PathVariable Long customerId, @RequestBody Products product) {
//        return cartService.removeProductToCart(customerId, product);
//    }

    @DeleteMapping("/{customerId}/clear")
    public void clearCart(@PathVariable Long customerId) {
        cartService.clearCart(customerId);
    }
}