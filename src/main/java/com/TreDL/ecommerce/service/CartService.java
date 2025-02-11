package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.CartRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CartService {

    @Autowired
    private CartRepository cartRepository;

    public Cart getCart(Long customerId) {
        return cartRepository.findByCustomerId(customerId);
    }


    public Cart addProductToCart(Long customerId, Products product) {
        Cart cart = getCart(customerId);
        cart.getProducts().add(product);
        return cartRepository.save(cart);
    }

    public Cart removeProductToCart(Long customerId, Products product) {
        Cart cart = getCart(customerId);
        cart.getProducts().remove(product);
        return cartRepository.save(cart);
    }

    public void clearCart(Long customerId) {
        Cart cart =cartRepository.findByCustomerId(customerId);
        cart.getProducts().clear();
        cartRepository.save(cart);
    }


}