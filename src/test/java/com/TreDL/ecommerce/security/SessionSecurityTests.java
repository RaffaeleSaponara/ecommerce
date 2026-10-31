package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.SecurityConfig;
import com.TreDL.ecommerce.controller.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.service.*;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.OrderItem;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// MVC slice: real security chain, password encoder and user lookup; no datasource or external services.
@WebMvcTest({AuthController.class, HomeController.class, CartController.class,
        CustomersController.class, ProductsController.class, OrdersController.class})
@Import({SecurityConfig.class, CustomUserDetailsService.class})
class SessionSecurityTests {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @MockitoBean CustomersRepository customersRepository;
    @MockitoBean CustomersService customersService;
    @MockitoBean CartService cartService;
    @MockitoBean ProductsService productsService;
    @MockitoBean CheckoutService checkoutService;
    @MockitoBean OrderHistoryService orderHistoryService;

    private Customers customer;
    private Products product;
    private Cart cart;

    @BeforeEach
    void fixtures() {
        customer = new Customers();
        customer.setEmail("alice@example.com");
        customer.setPassword(passwords.encode("test-password"));
        customer.setRole("USER");
        product = new Products();
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 7L);
        product.setPrezzo(new BigDecimal("12.50")); product.setNome("Jig");
        product.setDescrizione("Description"); product.setCategoria("Hardbaits"); product.setImage("/images/b1.jpg");
        cart = new Cart(3L, customer, List.of(product));
        // Cyclic graph deliberately includes a password hash and private relationships.
        customer.setCart(cart);
        product.setCarts(List.of(cart));
        when(customersRepository.findByEmail(customer.getEmail())).thenReturn(Optional.of(customer));
        when(customersService.getByEmail(customer.getEmail())).thenReturn(customer);
        when(productsService.getById(7L)).thenReturn(product);
        when(cartService.getCart(customer)).thenReturn(cart);
        when(cartService.getCartForView(customer)).thenReturn(cart);
        when(cartService.addProductToCart(customer, product)).thenReturn(cart);
    }

    @Test
    void loginPersistsAuthenticationAndRotatesSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String oldId = session.getId();
        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", customer.getEmail()).param("password", "test-password"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/"))
                .andExpect(cookie().doesNotExist("jwt"));
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/auth/check").session(session)).andExpect(content().string("true"));
        mvc.perform(get("/cart/view").session(session)).andExpect(status().isOk());
    }

    @Test
    void invalidCredentialsDoNotAuthenticate() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", customer.getEmail()).param("password", "wrong"))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(get("/auth/check").session(session)).andExpect(content().string("false"));
        mvc.perform(get("/cart/view").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void legacyJwtCookieDoesNotAuthenticate() throws Exception {
        String token = new JwtUtil(java.util.Base64.getEncoder().encodeToString(new byte[32]))
                .generateToken(org.springframework.security.core.userdetails.User
                        .withUsername(customer.getEmail()).password("unused").roles("USER").build());
        mvc.perform(get("/auth/check").cookie(new Cookie("jwt", token)))
                .andExpect(content().string("false"));
        mvc.perform(get("/cart/view").cookie(new Cookie("jwt", token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cartReadDoesNotCreatePersistentState() {
        var repository = mock(com.TreDL.ecommerce.repository.CartRepository.class);
        var customerRepository = mock(CustomersRepository.class);
        CartService service = new CartService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cartRepository", repository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "customersRepository", customerRepository);
        when(repository.findByCustomer(customer)).thenReturn(Optional.empty());
        Cart empty = service.getCartForView(customer);
        assertThat(empty.getId()).isNull();
        assertThat(empty.getProducts()).isEmpty();
        assertThat(com.TreDL.ecommerce.dto.CartDTO.convertToDTO(empty).getItems()).isEmpty();
        verify(repository).findByCustomer(customer);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(customerRepository);
    }

    @Test
    void emptyCartViewPreservesHttpContractWithoutInvokingMutations() throws Exception {
        when(cartService.getCartForView(customer)).thenReturn(new Cart(null, customer, List.of()));

        mvc.perform(get("/cart/view").with(user(customer.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(content().json("{\"id\":null,\"id_customer\":null,\"items\":[]}"));
        verify(cartService).getCartForView(customer);
        verifyNoMoreInteractions(cartService);
        verifyNoInteractions(checkoutService);
    }

    @Test
    void fetchAcceptsCsrfHeaderTakenFromRenderedPage() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .param("username", customer.getEmail()).param("password", "test-password"))
                .andReturn().getRequest().getSession(false);
        String html = mvc.perform(get("/cart").session(session)).andReturn().getResponse().getContentAsString();
        var token = java.util.regex.Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).isTrue();
        mvc.perform(post("/cart/add").session(session).header("X-CSRF-TOKEN", token.group(1))
                        .contentType(MediaType.APPLICATION_JSON).content("7"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/signup", "/logout", "/cart/add", "/cart/remove/7", "/cart/checkout", "/cart/quote"})
    void mutationsRejectMissingAndInvalidCsrf(String path) throws Exception {
        for (boolean invalid : new boolean[]{false, true}) {
            MockHttpServletRequestBuilder request = (path.contains("remove") ? delete(path) : post(path))
                    .with(user(customer.getEmail())).contentType(MediaType.APPLICATION_JSON).content("7");
            if (invalid) request.with(csrf().useInvalidToken());
            mvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(cartService, checkoutService, customersService);
    }

    @Test
    void anonymousCannotMutateEvenWithValidCsrf() throws Exception {
        mvc.perform(post("/cart/add").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("7"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/cart/checkout").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/cart/remove/7").with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(cartService, checkoutService);
    }

    @Test
    void cartResponsesContainOnlyExplicitDtoFields() throws Exception {
        String body = mvc.perform(post("/cart/add").with(user(customer.getEmail())).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].nome").value("Jig"))
                .andExpect(jsonPath("$.items[0].quantita").value(1))
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.items[0]", aMapWithSize(8)))
                .andExpect(jsonPath("$.items[0].stock").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password", customer.getPassword(), "orders", "carts", "\"customer\"");
        mvc.perform(get("/cart/view").with(user(customer.getEmail())))
                .andExpect(status().isOk()).andExpect(content().json(body));
        mvc.perform(delete("/cart/remove/7").with(user(customer.getEmail())).with(csrf().asHeader()))
                .andExpect(status().isOk());
        verify(cartService).removeProductToCart(customer, product);
    }

    @Test
    void checkoutGetAndHeadCannotCreateOrders() throws Exception {
        mvc.perform(get("/cart/checkout").with(user(customer.getEmail())))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(head("/cart/checkout").with(user(customer.getEmail())))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(checkoutService, cartService);
    }

    @Test
    void checkoutPostReturnsDtoWithoutCustomerOrJpaGraph() throws Exception {
        Orders order = new Orders();
        order.setCustomer(customer); order.setDate(LocalDateTime.of(2026, 1, 1, 12, 0));
        order.setTotal(new BigDecimal("12.50")); order.setShipment("In elaborazione");
        order.addItem(new OrderItem(order, 1, product, 1));
        customer.setOrders(List.of(order));
        when(checkoutService.checkout(eq(customer.getEmail()), eq("key"), any())).thenReturn(OrderDTO.convertToDTO(order));
        String body = mvc.perform(post("/cart/checkout").with(user(customer.getEmail())).with(csrf())
                        .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteToken\":\"signed-quote\",\"confirmPrices\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.total").value(12.5))
                .andExpect(jsonPath("$", aMapWithSize(8)))
                .andExpect(jsonPath("$.shippingAddress").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.billingAddress").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0]", aMapWithSize(5)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password", customer.getPassword(), "customer", "orders", "carts", "requestHash", "idempotencyKey");
        verify(checkoutService).checkout(customer.getEmail(), "key", new CheckoutRequest("signed-quote", true));
        verifyNoInteractions(cartService);
    }

    @Test
    void logoutInvalidatesSessionOnlyWithPostAndCsrf() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .param("username", customer.getEmail()).param("password", "test-password"))
                .andReturn().getRequest().getSession(false);
        mvc.perform(get("/logout").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/auth/check").session(session)).andExpect(content().string("true"));
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/auth/check")).andExpect(content().string("false"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/home", "/login", "/registrazione", "/prodotti", "/user", "/cart"})
    void renderedPagesContainCsrfProtectedPostForms(String path) throws Exception {
        String html = mvc.perform(get(path).with(user(customer.getEmail()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertProtectedForm(html, "/logout");
        if (path.equals("/login") || path.equals("/registrazione")) {
            String action = path.equals("/login") ? "/login" : "/signup";
            assertProtectedForm(html, action);
        }
        if (path.equals("/cart")) {
            assertProtectedForm(html, "/cart/checkout");
        }
        if (path.equals("/cart") || path.equals("/prodotti")) {
            assertThat(html).containsPattern("<meta name=\"_csrf\" content=\"[^\"]+\"")
                    .contains("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"");
        }
    }

    private void assertProtectedForm(String html, String action) {
        var form = java.util.regex.Pattern.compile("<form[^>]*method=\"post\"[^>]*action=\""
                + java.util.regex.Pattern.quote(action) + "\"[^>]*>(.*?)</form>",
                java.util.regex.Pattern.DOTALL).matcher(html);
        assertThat(form.find()).as("POST form for %s", action).isTrue();
        assertThat(form.group(1)).containsPattern("<input[^>]*name=\"_csrf\"[^>]*value=\"[^\"]+\"");
    }

    @Test
    void signupAcceptsValidCsrfAndStoresOnlyEncodedPasswordWithUserRole() throws Exception {
        when(customersService.getByEmail("new@example.com")).thenReturn(new Customers());
        mvc.perform(post("/signup").with(csrf()).param("username", "New")
                        .param("email", "new@example.com").param("password", "new-password")
                        .param("confirmPassword", "new-password").param("role", "ADMIN"))
                .andExpect(redirectedUrl("/login"));
        verify(customersService).addCustomer(argThat(saved ->
                passwords.matches("new-password", saved.getPassword()) && saved.getRole().equals("USER")));
    }

    @Test
    void signupExplainsAnAlreadyRegisteredEmailWithoutCreatingAnotherAccount() throws Exception {
        mvc.perform(post("/signup").with(csrf()).param("username", "Alice")
                        .param("email", customer.getEmail()).param("password", "test-password")
                        .param("confirmPassword", "test-password"))
                .andExpect(redirectedUrl("/registrazione?error=email"));
        verify(customersService, never()).addCustomer(any());
        mvc.perform(get("/registrazione").param("error", "email"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Questa email è già registrata")))
                .andExpect(content().string(not(containsString("Le due password non coincidono"))));
    }

    @Test
    void signupExplainsMismatchedPasswordsWithoutSavingEitherPassword() throws Exception {
        when(customersService.getByEmail("new@example.com")).thenReturn(new Customers());
        mvc.perform(post("/signup").with(csrf()).param("username", "New")
                        .param("email", "new@example.com").param("password", "first-secret")
                        .param("confirmPassword", "different-secret"))
                .andExpect(redirectedUrl("/registrazione?error=password"));
        verify(customersService, never()).addCustomer(any());
        mvc.perform(get("/registrazione").param("error", "password"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Le due password non coincidono")))
                .andExpect(content().string(not(containsString("Questa email è già registrata"))))
                .andExpect(content().string(not(containsString("first-secret"))))
                .andExpect(content().string(not(containsString("different-secret"))));
    }

    @Test
    void publicCatalogUsesProductDtos() throws Exception {
        when(productsService.getAllProducts()).thenReturn(List.of(product));
        mvc.perform(get("/prodotti/all")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0]", aMapWithSize(8)))
                .andExpect(jsonPath("$[0].stock").value(0))
                .andExpect(jsonPath("$[0].carts").doesNotExist());
    }
}
