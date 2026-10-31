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
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class CartService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private CartRepository cartRepository;
    @Autowired
    private CustomersRepository customersRepository;

    @Transactional(readOnly = true)
    public Cart getCartForView(Customers customer) {
        Cart cart = cartRepository.findByCustomer(customer)
                .orElseGet(() -> new Cart(null, customer, List.of()));
        // Materialize the view inside the read-only transaction, without locking.
        if (cart.getProducts() != null) {
            cart.getProducts().size();
        }
        return cart;
    }

    public Cart getCart(Customers customer) {
        Customers lockedCustomer = lockCustomer(customer.getId());
        return cartRepository.findByCustomer(lockedCustomer).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setCustomer(lockedCustomer);
            return cartRepository.save(cart);
        });
    }


    public Cart addProductToCart(Customers customer, Products product) {
        Cart cart = getCart(customer);
        // The cart is not a reservation: read fresh stock without blocking other carts.
        // The customer's lock serializes quantity changes; checkout locks inventory for consumption.
        Products current = entityManager.find(Products.class, product.getId());
        if (current == null) throw new CheckoutException(org.springframework.http.HttpStatus.CONFLICT, "Prodotto non più disponibile");
        entityManager.refresh(current);
        long quantity = cart.getProducts() == null ? 0 : cart.getProducts().stream()
                .filter(item -> java.util.Objects.equals(item.getId(), current.getId())).count();
        if (quantity >= current.getStock()) throw new CheckoutException(org.springframework.http.HttpStatus.CONFLICT,
                "Disponibilità insufficiente per " + current.getNome() + ": " + current.getStock() + " unità disponibili");
        if(cart.getProducts()!=null){
            cart.getProducts().add(current);
        }else{
            List<Products> prodotti = new ArrayList<>();
            prodotti.add(current);
            cart.setProducts(prodotti);
        }
        return cartRepository.save(cart);
    }

    public Cart removeProductToCart(Customers customer, Products product) {
        Cart cart = getCart(customer);
        if (cart.getProducts() != null) {
            cart.getProducts().stream()
                    .filter(item -> item.getId().equals(product.getId()))
                    .findFirst()
                    .ifPresent(item -> cart.getProducts().remove(item));
        }
        return cartRepository.save(cart);
    }

    public void clearCart(Long customerId) {
        lockCustomer(customerId);
        Optional<Cart> cart =cartRepository.findByCustomerId(customerId);
        if (cart.isPresent() && cart.get().getProducts() != null) {
            cart.get().getProducts().clear();
            cartRepository.save(cart.get());
        }
    }

    private Customers lockCustomer(Long customerId) {
        // Every cart mutation must first lock the same stable row, including creation.
        // The enclosing service transaction retains this lock through commit/rollback.
        return customersRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));
    }

}
