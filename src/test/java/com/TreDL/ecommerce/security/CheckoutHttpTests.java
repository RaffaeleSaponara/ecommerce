package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.SecurityConfig;
import com.TreDL.ecommerce.controller.CartController;
import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Orders;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.CartRepository;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.service.CartService;
import com.TreDL.ecommerce.service.CustomUserDetailsService;
import com.TreDL.ecommerce.service.CustomersService;
import com.TreDL.ecommerce.service.CheckoutService;
import com.TreDL.ecommerce.service.ProductsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.OrderItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Real MVC, session security, DTO conversion and cart service; repository writes remain mocked.
@WebMvcTest(CartController.class)
@Import({SecurityConfig.class, CustomUserDetailsService.class, CartService.class})
@ActiveProfiles("test")
class CheckoutHttpTests {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @MockitoBean CustomersRepository customersRepository;
    @MockitoBean CartRepository cartRepository;
    @MockitoBean CustomersService customersService;
    @MockitoBean ProductsService productsService;
    @MockitoBean CheckoutService checkoutService;

    private Customers customer;
    private Products product;
    private Cart cart;

    @BeforeEach
    void fixtures() {
        customer = new Customers(41L, null, null, "Checkout customer",
                passwords.encode("checkout-password"), "checkout@example.com", "USER");
        product = new Products();
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 7L);
        product.setPrezzo(new BigDecimal("12.50")); product.setNome("Jig");
        product.setDescrizione("Description"); product.setCategoria("Hardbaits"); product.setImage("/images/b1.jpg");
        cart = new Cart(3L, customer, new ArrayList<>(List.of(product, product)));
        // Cycles and credentials must never escape through the order DTO.
        customer.setCart(cart);
        product.setCarts(List.of(cart));
    }

    @Test
    void postWithLoginSessionAndCsrfDelegatesToAtomicCheckoutAndReturnsOnlyDto() throws Exception {
        MockHttpSession session = login();
        Orders order = new Orders();
        order.setCustomer(customer); order.setDate(LocalDateTime.of(2026, 10, 27, 12, 0));
        order.setTotal(new BigDecimal("25.00")); order.setShipment("In elaborazione");
        order.addItem(new OrderItem(order, 1, product, 2));
        customer.setOrders(List.of(order));
        when(checkoutService.checkout(eq(customer.getEmail()), eq("client-key"), any()))
                .thenReturn(OrderDTO.convertToDTO(order));

        String body = mvc.perform(post("/cart/checkout").session(session).with(csrf())
                        .header("Idempotency-Key", "client-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteToken\":\"signed-quote\",\"confirmPrices\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.date").value("2026-10-27T12:00:00"))
                .andExpect(jsonPath("$.total").isNumber()).andExpect(jsonPath("$.total").value(25.0))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.shipment").value("In elaborazione"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").isNumber())
                .andExpect(jsonPath("$.items[0].subtotal").value(25.0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password", "checkout-password", customer.getPassword(),
                "customer", "orders", "carts", "hibernateLazyInitializer", "handler", "requestHash", "idempotencyKey");
        verify(checkoutService).checkout(customer.getEmail(), "client-key", new CheckoutRequest("signed-quote", true));
        verifyNoMoreInteractions(checkoutService);
        verifyNoInteractions(customersService, cartRepository, productsService);
        // The controller performs no separate mutation outside the service transaction.
        assertThat(cart.getProducts()).containsExactly(product, product);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void checkoutRejectsReadOnlyMethodsWithoutTouchingCartOrOrders(String method) throws Exception {
        MockHttpSession session = login();
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/cart/checkout").session(session))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        assertThat(cart.getProducts()).containsExactly(product, product);
        verifyNoInteractions(customersService, cartRepository, checkoutService, productsService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authenticatedPostRejectsMissingOrInvalidCsrfBeforeCallingServices(boolean invalid) throws Exception {
        MockHttpSession session = login();
        var request = post("/cart/checkout").session(session);
        if (invalid) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        assertThat(cart.getProducts()).containsExactly(product, product);
        verifyNoInteractions(customersService, cartRepository, checkoutService, productsService);
    }

    @Test
    void anonymousPostWithValidCsrfRequiresAuthentication() throws Exception {
        mvc.perform(post("/cart/checkout").with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(customersService, cartRepository, checkoutService, productsService);
    }

    @Test
    void failedLoginDoesNotAuthorizeCheckoutEvenWithValidCsrf() throws Exception {
        when(customersRepository.findByEmail(customer.getEmail())).thenReturn(Optional.of(customer));
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", customer.getEmail()).param("password", "wrong-password"))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/cart/checkout").session(session).with(csrf()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(customersService, cartRepository, checkoutService, productsService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void readingExistingCartDoesNotCreateOrdersOrMutateCart(String method) throws Exception {
        MockHttpSession session = login();
        when(customersService.getByEmail(customer.getEmail())).thenReturn(customer);
        when(cartRepository.findByCustomer(customer)).thenReturn(Optional.of(cart));
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/cart/view").session(session))
                .andExpect(status().isOk());
        assertThat(cart.getId()).isEqualTo(3L);
        assertThat(cart.getProducts()).containsExactly(product, product);
        verify(cartRepository).findByCustomer(customer);
        verifyNoMoreInteractions(cartRepository);
        verifyNoInteractions(checkoutService, productsService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void readingAbsentCartDoesNotPersistAnEmptyCart(String method) throws Exception {
        MockHttpSession session = login();
        customer.setCart(null);
        when(customersService.getByEmail(customer.getEmail())).thenReturn(customer);
        when(cartRepository.findByCustomer(customer)).thenReturn(Optional.empty());
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/cart/view").session(session))
                .andExpect(status().isOk());
        assertThat(customer.getCart()).isNull();
        verify(cartRepository).findByCustomer(customer);
        verifyNoMoreInteractions(cartRepository);
        verifyNoInteractions(checkoutService, productsService);
    }

    private MockHttpSession login() throws Exception {
        when(customersRepository.findByEmail(customer.getEmail())).thenReturn(Optional.of(customer));
        return (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .param("username", customer.getEmail()).param("password", "checkout-password"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/"))
                .andReturn().getRequest().getSession(false);
    }
}
