package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.dto.CartDTO;
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

    @GetMapping("/view")
    public CartDTO getCart(Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        return CartDTO.convertToDTO(cartService.getCart(customer));
    }

    @PostMapping("/add")
    public Cart addProduct(@RequestBody Long productId, Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        Products product = productsService.getById(productId);
        return cartService.addProductToCart(customer, product);
    }

    @DeleteMapping("/remove/{productId}")
    public void removeProduct(@PathVariable Long productId, Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        Products product = productsService.getById(productId);
        cartService.removeProductToCart(customer, product);
    }
}