package com.TreDL.ecommerce.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"payments.stripe.enabled=true","payments.stripe.secret-key=sk_test_fixture_only","payments.stripe.scheduler.enabled=false"})
@AutoConfigureMockMvc
class StripeCustomerGateTests extends StockCheckoutContract {
    @Autowired StripeCustomerCheckout customerCheckout;
    @Autowired MockMvc mvc;
    @Test void enablingInternalStripeDoesNotExposeCustomerCheckout() throws Exception {
        assertThat(customerCheckout.available()).isFalse();
        mvc.perform(post("/cart/stripe/checkout").with(user(alice.getEmail())).with(csrf())
            .header("Idempotency-Key","disabled").contentType("application/json").content("{}"))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/user/orders/1/payment/resume").with(user(alice.getEmail())).with(csrf()))
            .andExpect(status().isServiceUnavailable());
        assertThat(orders.count()).isZero();assertThat(available()).isEqualTo(5);
    }
}
