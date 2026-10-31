package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.CartRepository;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class CartServiceUnitTests {
    @Mock private CartRepository carts;
    @Mock private CustomersRepository customers;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @InjectMocks private CartService service;

    private final Customers customer = new Customers();
    private final Products product = new Products();

    void lockExistingCustomer() {
        when(customers.findByIdForUpdate(any())).thenReturn(Optional.of(customer));
    }
    void availableProduct() {
        product.setStock(100);
        when(entityManager.find(Products.class, product.getId())).thenReturn(product);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "add", "remove", "clear"})
    void locksCustomerBeforeReadingCart(String operation) {
        if (operation.equals("clear")) {
            lockExistingCustomer();
            when(carts.findByCustomerId(42L)).thenReturn(Optional.empty());
            service.clearCart(42L);
            var order = inOrder(customers, carts);
            order.verify(customers).findByIdForUpdate(42L);
            order.verify(carts).findByCustomerId(42L);
        } else {
            existingCart(null);
            switch (operation) {
                case "create" -> service.getCart(customer);
                case "add" -> { availableProduct(); service.addProductToCart(customer, product); }
                case "remove" -> service.removeProductToCart(customer, product);
                default -> throw new AssertionError(operation);
            }
            var order = inOrder(customers, carts);
            order.verify(customers).findByIdForUpdate(customer.getId());
            order.verify(carts).findByCustomer(customer);
        }
    }

    @Test
    void missingCustomerFailsBeforeAccessingCart() {
        when(customers.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCart(customer))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(carts);
    }

    @Test
    void createsCartForCustomerWhenMissing() {
        lockExistingCustomer();
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
        availableProduct();
        Cart cart = existingCart(null);
        when(carts.save(cart)).thenReturn(cart);

        assertThat(service.addProductToCart(customer, product).getProducts()).containsExactly(product);

        verify(carts).save(cart);
    }

    @Test
    void appendsToExistingProductCollection() {
        availableProduct();
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
        lockExistingCustomer();
        when(carts.findByCustomerId(42L)).thenReturn(Optional.empty());

        service.clearCart(42L);

        verify(carts, never()).save(any());
    }

    @Test
    void clearWithNullCollectionDoesNotSave() {
        lockExistingCustomer();
        when(carts.findByCustomerId(42L)).thenReturn(Optional.of(new Cart()));

        service.clearCart(42L);

        verify(carts, never()).save(any());
    }

    @Test
    void clearRemovesAllOccurrencesAndSavesCart() {
        lockExistingCustomer();
        Cart cart = new Cart();
        cart.setProducts(new ArrayList<>(List.of(product, product)));
        when(carts.findByCustomerId(42L)).thenReturn(Optional.of(cart));

        service.clearCart(42L);

        assertThat(cart.getProducts()).isEmpty();
        verify(carts).save(cart);
    }

    private Cart existingCart(List<Products> items) {
        lockExistingCustomer();
        Cart cart = new Cart();
        cart.setCustomer(customer);
        cart.setProducts(items);
        when(carts.findByCustomer(customer)).thenReturn(Optional.of(cart));
        return cart;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void viewOnlyReadsCartWithoutCustomerLockOrWrites(boolean exists) throws Exception {
        Cart persisted = new Cart(9L, customer, new ArrayList<>(List.of(product, product)));
        when(carts.findByCustomer(customer)).thenReturn(exists ? Optional.of(persisted) : Optional.empty());

        Cart result = service.getCartForView(customer);

        assertThat(result.getCustomer()).isSameAs(customer);
        if (exists) {
            assertThat(result).isSameAs(persisted);
            assertThat(result.getProducts()).containsExactly(product, product);
        } else {
            assertThat(result.getId()).isNull();
            assertThat(result.getProducts()).isEmpty();
        }
        assertThat(CartService.class.getMethod("getCartForView", Customers.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
        verify(carts).findByCustomer(customer);
        verifyNoMoreInteractions(carts);
        verifyNoInteractions(customers);
    }
}
