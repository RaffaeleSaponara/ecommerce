package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.CartRepository;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class CartService {

    @Autowired
    private CartRepository cartRepository;
    @Autowired
    private CustomersRepository customersRepository;

    public Cart getCart(Customers customer) {
        if(cartRepository.findByCustomer(customer).isPresent()){
            return cartRepository.findByCustomer(customer).get();
        }else{
            Cart cart = new Cart();
            cart.setCustomer(customer);
            return cartRepository.save(cart);
        }
    }


    public Cart addProductToCart(Customers customer, Products product) {
        Cart cart = getCart(customer);
        if(cart.getProducts()!=null){
            cart.getProducts().add(product);
        }else{
            List<Products> prodotti = new ArrayList<>();
            prodotti.add(product);
            cart.setProducts(prodotti);
        }
        return cartRepository.save(cart);
    }

    public Cart removeProductToCart(Customers customer, Products product) {
        Cart cart = getCart(customer);
        cart.getProducts().remove(product);
        return cartRepository.save(cart);
    }

    public void clearCart(Long customerId) {
        Optional<Cart> cart =cartRepository.findByCustomerId(customerId);
        cart.get().getProducts().clear();
        cartRepository.save(cart.get());
    }


}