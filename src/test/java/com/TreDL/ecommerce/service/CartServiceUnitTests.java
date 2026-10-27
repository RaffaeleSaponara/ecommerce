package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.CartRepository;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class CartServiceUnitTests {
    @Mock private CartRepository carts;
    @Mock private CustomersRepository customers;
    @InjectMocks private CartService service;

    private final Customers customer = new Customers();
    private final Products product = new Products();

    @Test
    void createsCartForCustomerWhenMissing() {
        when(carts.findByCustomer(customer)).thenReturn(Optional.empty());
        when(carts.save(any(Cart.class))).thenAnswer(call -> call.getArgument(0));

        Cart result = service.getCart(customer);

        assertThat(result.getCustomer()).isSameAs(customer);
        verify(carts).save(result);
    }

    @Test
    void existingCartIsReturnedWithoutSaving() {
        Cart cart = existingCart(null);

        assertThat(service.getCart(customer)).isSameAs(cart);

        verify(carts, never()).save(any());
    }

    @Test
    void initializesNullProductCollectionOnAddition() {
        Cart cart = existingCart(null);
        when(carts.save(cart)).thenReturn(cart);

        assertThat(service.addProductToCart(customer, product).getProducts()).containsExactly(product);

        verify(carts).save(cart);
    }

    @Test
    void appendsToExistingProductCollection() {
        Cart cart = existingCart(new ArrayList<>(List.of(product)));
        when(carts.save(cart)).thenReturn(cart);

        assertThat(service.addProductToCart(customer, product).getProducts())
                .containsExactly(product, product);

        verify(carts).save(cart);
    }

    @Test
    void removalHandlesNullProductCollection() {
        Cart cart = existingCart(null);
        when(carts.save(cart)).thenReturn(cart);

        assertThat(service.removeProductToCart(customer, product)).isSameAs(cart);

        verify(carts).save(cart);
    }

    @Test
    void clearWithoutCartDoesNotSave() {
        when(carts.findByCustomerId(42L)).thenReturn(Optional.empty());

        service.clearCart(42L);

        verify(carts, never()).save(any());
    }

    @Test
    void clearWithNullCollectionDoesNotSave() {
        when(carts.findByCustomerId(42L)).thenReturn(Optional.of(new Cart()));

        service.clearCart(42L);

        verify(carts, never()).save(any());
    }

    @Test
    void clearRemovesAllOccurrencesAndSavesCart() {
        Cart cart = new Cart();
        cart.setProducts(new ArrayList<>(List.of(product, product)));
        when(carts.findByCustomerId(42L)).thenReturn(Optional.of(cart));

        service.clearCart(42L);

        assertThat(cart.getProducts()).isEmpty();
        verify(carts).save(cart);
    }

    private Cart existingCart(List<Products> items) {
        Cart cart = new Cart();
        cart.setCustomer(customer);
        cart.setProducts(items);
        when(carts.findByCustomer(customer)).thenReturn(Optional.of(cart));
        return cart;
    }
}
