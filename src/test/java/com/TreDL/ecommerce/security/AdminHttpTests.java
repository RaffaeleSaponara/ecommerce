package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminHttpTests {
    @Autowired MockMvc mvc;
    @Autowired ProductsRepository products;
    @Autowired OrdersRepository orders;
    @Autowired CustomersRepository customers;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;

    private static final String PRODUCT = "{\"nome\":\"Jig\",\"prezzo\":12.50,\"descrizione\":\"Esca\",\"categoria\":\"Jig\",\"image\":\"/images/b1.jpg\"}";

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/dashboard", "/api/admin/products", "/api/admin/orders", "/api/admin/orders/1"})
    void anonymousCannotReadAdminApis(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCannotWriteEvenWithCsrf() throws Exception {
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/products/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/unknown", "/api/admin/dashboard", "/api/admin/products", "/api/admin/orders", "/api/admin/orders/1"})
    @WithMockUser(roles = "USER")
    void customerCannotReadAdminRoutes(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void customerCannotCreateOrModifyProductsWithValidCsrf() throws Exception {
        long count = products.count();
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/products/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isForbidden());
        assertThat(products.count()).isEqualTo(count);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void csrfProtectsEveryProductMutation() throws Exception {
        for (var request : java.util.List.of(post("/api/admin/products"), put("/api/admin/products/1"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(PRODUCT)).andExpect(status().isForbidden());
            mvc.perform(request.with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCrudUsesPersistedProductsAndDashboardStartsAtZero() throws Exception {
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(0)).andExpect(jsonPath("$.totalProducts").value(0))
                .andExpect(jsonPath("$.confirmedOrderValue").value(0)).andExpect(jsonPath("$.currency").value("EUR"));
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nome").value("Jig"));
        Products product = products.findAll().get(0);
        mvc.perform(put("/api/admin/products/" + product.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCT.replace("12.50", "21.75")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.prezzo").value(21.75));
        products.flush();
        assertThat(products.findById(product.getId()).orElseThrow().getPrezzo()).isEqualByComparingTo("21.75");
        mvc.perform(get("/api/admin/products")).andExpect(status().isOk()).andExpect(jsonPath("$[0].prezzo").value(21.75));
        mvc.perform(get("/api/admin/dashboard")).andExpect(jsonPath("$.totalProducts").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void dashboardSumsPersistedTotalsAndOrderDetailKeepsHistoricalSnapshots() throws Exception {
        Customers customer = customer("USER");
        Products product = new Products();
        product.setNome("Original jig"); product.setPrezzo(new BigDecimal("12.50")); products.saveAndFlush(product);
        Orders first = order(customer, product, "one", "In elaborazione");
        order(customer, product, "two", "Spedito");
        product.setNome("Changed jig"); product.setPrezzo(new BigDecimal("99.00")); products.flush();
        mvc.perform(get("/api/admin/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(2)).andExpect(jsonPath("$.totalProducts").value(1))
                .andExpect(jsonPath("$.confirmedOrderValue").value(50.00));
        mvc.perform(get("/api/admin/orders")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        String body = mvc.perform(get("/api/admin/orders/" + first.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.shipment").value("In elaborazione"))
                .andExpect(jsonPath("$.items[0].productName").value("Original jig"))
                .andExpect(jsonPath("$.items[0].subtotal").value(25.0)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password", "idempotencyKey", "requestHash", "customer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nome\":\"  \"}", "{\"nome\":\"Jig\",\"prezzo\":-1}", "{\"nome\":\"Jig\",\"prezzo\":0.001}", "{\"nome\":\"Jig\",\"prezzo\":null}", "{\"nome\":\"Jig\",\"prezzo\":100000000000000000}"})
    @WithMockUser(roles = "ADMIN")
    void invalidProductDoesNotPersist(String body) throws Exception {
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        assertThat(products.count()).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void missingResourcesAndOversizedTextHaveUsefulErrors() throws Exception {
        mvc.perform(get("/api/admin/orders/999999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isString());
        mvc.perform(put("/api/admin/products/999999").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUCT.replace("Esca", "x".repeat(256))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void persistedAdminCanUseNormalLoginSession() throws Exception {
        Customers admin = customer("ADMIN");
        var session = mvc.perform(post("/login").with(csrf()).param("username", admin.getEmail()).param("password", "test-password"))
                .andExpect(status().isFound()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/admin/dashboard").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPageRendersAndCustomerAreaOffersAdminNavigation() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isOk()).andExpect(view().name("admin"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        mvc.perform(get("/user")).andExpect(status().isOk()).andExpect(model().attribute("isAdmin", true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin\"")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void customerAreaDoesNotExposeAdminNavigation() throws Exception {
        mvc.perform(get("/user")).andExpect(status().isOk()).andExpect(model().attribute("isAdmin", false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("href=\"/admin\""))));
    }

    private Customers customer(String role) {
        Customers customer = new Customers();
        customer.setUsername("test-" + role); customer.setEmail(role + "@example.test");
        customer.setPassword(passwords.encode("test-password")); customer.setRole(role);
        return customers.saveAndFlush(customer);
    }

    private Orders order(Customers customer, Products product, String key, String shipment) {
        Orders order = new Orders(); order.setCustomer(customer); order.setDate(LocalDateTime.of(2026, 10, 28, 12, 0));
        order.setShipment(shipment); order.setTotal(new BigDecimal("25.00"));
        order.setIdempotencyKey(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)); order.setRequestHash(new byte[32]);
        order.addItem(new OrderItem(order, 1, product, 2));
        return orders.saveAndFlush(order);
    }
}

