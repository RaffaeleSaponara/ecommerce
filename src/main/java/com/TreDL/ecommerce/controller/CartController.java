package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.dto.CartDTO;
import com.TreDL.ecommerce.dto.OrderDTO;
import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.service.CartService;
import com.TreDL.ecommerce.service.CustomersService;
import com.TreDL.ecommerce.service.CheckoutService;
import com.TreDL.ecommerce.dto.CheckoutRequest;
import com.TreDL.ecommerce.dto.QuoteDTO;
import com.TreDL.ecommerce.service.ProductsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

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
    private CheckoutService checkoutService;

    @GetMapping("/view")
    public CartDTO getCart(Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        return CartDTO.convertToDTO(cartService.getCartForView(customer));
    }

    @PostMapping("/add")
    public CartDTO addProduct(@RequestBody Long productId, Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        Products product = productsService.getById(productId);
        return CartDTO.convertToDTO(cartService.addProductToCart(customer, product));
    }

    @DeleteMapping("/remove/{productId}")
    public void removeProduct(@PathVariable Long productId, Principal principal) {
        Customers customer= customersService.getByEmail(principal.getName());
        Products product = productsService.getById(productId);
        cartService.removeProductToCart(customer, product);
    }

    @PostMapping("/quote")
    public QuoteDTO quote(Principal principal) {
        return checkoutService.quote(principal.getName());
    }

    @PostMapping(value = "/checkout", consumes = "application/json")
    public OrderDTO checkout(@RequestHeader("Idempotency-Key") String key,
                             @RequestBody CheckoutRequest request, Principal principal) {
        return checkoutService.checkout(principal.getName(), key, request);
    }
}
