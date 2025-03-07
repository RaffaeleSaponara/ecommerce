package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.dto.CartDTO;
import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Orders;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.service.CartService;
import com.TreDL.ecommerce.service.CustomersService;
import com.TreDL.ecommerce.service.OrdersService;
import com.TreDL.ecommerce.service.ProductsService;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
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
    @Autowired
    private OrdersService ordersService;

    @GetMapping("/view")
    public CartDTO getCart(@NotNull Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        return CartDTO.convertToDTO(cartService.getCart(customer));
    }
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/add")
    public Cart addProduct(@RequestBody Long productId, Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        Products product = productsService.getById(productId);
        return cartService.addProductToCart(customer, product);
    }
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/remove/{productId}")
    public void removeProduct(@PathVariable Long productId, Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        Products product = productsService.getById(productId);
        cartService.removeProductToCart(customer, product);
    }
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/checkout")
    public ResponseEntity<?> checkout(Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        Cart cart = cartService.getCart(customer);
        Orders order = ordersService.createOrder(cart);
        cartService.clearCart(customer.getId());
        return ResponseEntity.ok("Ordine andato a buon fine!");
    }
}