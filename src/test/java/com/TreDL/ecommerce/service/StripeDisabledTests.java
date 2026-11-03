package com.TreDL.ecommerce.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.TreDL.ecommerce.model.PaymentFlow;
import com.TreDL.ecommerce.repository.PaymentAttemptsRepository;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class StripeDisabledTests extends StockCheckoutContract {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentAttemptsRepository payments;
    @Test void stripeDomainHasNoBeanInTheNormalApplication() {
        assertThat(context.getEnvironment().getProperty("payments.stripe.enabled",Boolean.class,false)).isFalse();
        assertThat(context.getBeansOfType(StripePaymentDomain.class)).isEmpty();
    }

    @Test void httpCheckoutCannotSelectStripeOrSimulatePaymentThroughItsPayload() throws Exception {
        carts.addProductToCart(alice,product);
        var signed=request(alice);
        var result=mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).with(csrf())
                .header("Idempotency-Key","http-legacy").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("quoteToken",signed.quoteToken(),"confirmPrices",true,
                        "shippingAddressId",aliceAddress,"paymentFlow","STRIPE_TEST","paymentStatus","SUCCEEDED"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shipment").value("CONFERMATO"))
                .andReturn();
        long id=mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(orders.findById(id).orElseThrow().getPaymentFlow()).isEqualTo(PaymentFlow.LEGACY);
        assertThat(payments.count()).isZero();
        assertThat(available()).isEqualTo(4);
    }
}
