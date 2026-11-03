package com.TreDL.ecommerce.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.TreDL.ecommerce.model.PaymentStatus;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"payments.stripe.enabled=true","payments.stripe.secret-key=sk_test_fixture_only",
    "payments.stripe.scheduler.enabled=false","payments.stripe.customer-checkout.enabled=true"})
class StripeCustomerCheckoutTests extends StripeSessionsTests {
    @Autowired StripeCustomerCheckout customerCheckout;
    @Autowired ObjectMapper mapper;

    @Test void zeroTotalIsRejectedBeforeReservationWhileLegacyKeepsItsBehavior() {
        product.setPrezzo(new java.math.BigDecimal("0.00"));products.saveAndFlush(product);
        jdbc.update("UPDATE shipping_settings SET cost=0 WHERE id=1");
        try {
            carts.addProductToCart(alice,product);var signed=request(alice);
            assertThatThrownBy(()->customerCheckout.start(alice.getEmail(),"zero-stripe",signed))
                .isInstanceOf(CheckoutException.class);
            assertThat(orders.count()).isZero();assertThat(payments.count()).isZero();
            assertThat(available()).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_products",Long.class)).isEqualTo(1);
            verifyNoInteractions(gateway);
            assertThat(checkout.checkout(alice.getEmail(),"zero-legacy",signed).shipment()).isEqualTo("CONFERMATO");
        } finally {jdbc.update("UPDATE shipping_settings SET cost=5.90 WHERE id=1");}
    }

    @Test void startTimeoutCanBeResumedFromHistoryWithANewBrowserSession() throws Exception {
        carts.addProductToCart(alice,product);var signed=request(alice);
        doThrow(new SessionFailure(false)).when(gateway).create(anyMap(),anyString());
        var result=customerCheckout.start(alice.getEmail(),"lost-session-response",signed);
        long orderId=result.state().orderId();
        assertThat(result.redirectUrl()).isNull();assertThat(result.state().resumable()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_products",Long.class)).isZero();
        mvc.perform(get("/user").session(new org.springframework.mock.web.MockHttpSession()).with(user(alice.getEmail())))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/user/orders/"+orderId+"/payment")));
        doAnswer(call->{frozen=call.getArgument(0);return response(frozen);}).when(gateway).create(anyMap(),anyString());
        mvc.perform(post("/user/orders/"+orderId+"/payment/resume")
            .session(new org.springframework.mock.web.MockHttpSession()).with(user(alice.getEmail())).with(csrf()))
            .andExpect(status().isSeeOther()).andExpect(redirectedUrl("https://checkout.stripe.com/c/test"));
        assertThat(orders.count()).isEqualTo(1);assertThat(payments.count()).isEqualTo(1);
        assertThat(available()).isEqualTo(4);verify(mail,never()).confirmed(any());
    }

    @Test void authenticatedHttpStartUsesServerSnapshotsAndIgnoresClientIdentityAndAmounts() throws Exception {
        carts.addProductToCart(alice,product);var signed=request(alice);
        doAnswer(call->{id=payments.findAll().get(0).getId();frozen=call.getArgument(0);return response(frozen);})
            .when(gateway).create(anyMap(),anyString());
        var body=new java.util.HashMap<String,Object>();
        body.put("quoteToken",signed.quoteToken());body.put("confirmPrices",true);body.put("shippingAddressId",aliceAddress);
        body.put("customerId",bob.getId());body.put("total",0.01);body.put("successUrl","https://untrusted.invalid");
        mvc.perform(post("/cart/stripe/checkout").with(user(alice.getEmail())).with(csrf())
            .header("Idempotency-Key","http-stripe").contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.redirectUrl").value("https://checkout.stripe.com/c/test"))
            .andExpect(jsonPath("$.state.orderStatus").value("IN_ATTESA_PAGAMENTO"));
        assertThat(orders.findAll().get(0).getCustomer().getId()).isEqualTo(alice.getId());
        assertThat(attempt(orders.findAll().get(0).getId()).getAmount()).isEqualByComparingTo("18.40");
        assertThat(frozen.get("success_url").toString()).endsWith("/payment/success").doesNotContain("untrusted");
        assertThat(available()).isEqualTo(4);verify(mail,never()).confirmed(any());
    }
    @Test void oldFrozenReturnUrlsAreReusedWithoutRewritingTheSessionRequest(){
        order();sessions.obtain(id);
        String snapshot=payments.findById(id).orElseThrow().getSessionRequest();
        String old=snapshot.replaceAll("/user/orders/[0-9]+/payment/success","/user?checkoutReturn=success")
            .replaceAll("/user/orders/[0-9]+/payment/cancel","/user?checkoutReturn=cancel");
        jdbc.update("UPDATE payment_attempts SET session_request=? WHERE id=?",old,id);
        customerCheckout.resume(alice.getEmail(),orders.findAll().get(0).getId());
        assertThat(payments.findById(id).orElseThrow().getSessionRequest()).isEqualTo(old);
        verify(gateway,times(1)).create(anyMap(),anyString());
    }

    @Test void customerStartAndReplayAfterCartClearedUseOneOrderAndOneSession() {
        carts.addProductToCart(alice,product);var signed=request(alice);
        // The gateway fixture checks the exact attempt UUID; discover it only after reservation.
        doAnswer(call->{id=payments.findAll().get(0).getId();frozen=call.getArgument(0);return response(frozen);})
            .when(gateway).create(anyMap(),anyString());
        var first=customerCheckout.start(alice.getEmail(),"customer-start",signed);
        assertThat(first.redirectUrl()).isEqualTo("https://checkout.stripe.com/c/test");
        var replay=customerCheckout.start(alice.getEmail(),"customer-start",signed);
        assertThat(replay.state().orderId()).isEqualTo(first.state().orderId());
        assertThat(orders.count()).isEqualTo(1);assertThat(payments.count()).isEqualTo(1);
        assertThat(available()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_products",Long.class)).isZero();
        verify(mail,never()).confirmed(any());
    }
    @Test void timeoutLeavesRecoverableOrderWithoutRequiringCartOrBrowserStorage(){
        order();doThrow(new SessionFailure(false)).when(gateway).create(anyMap(),anyString());
        var failed=customerCheckout.resume(alice.getEmail(),orders.findAll().get(0).getId());
        assertThat(failed.redirectUrl()).isNull();assertThat(failed.state().resumable()).isTrue();
        doAnswer(call->{frozen=call.getArgument(0);return response(frozen);}).when(gateway).create(anyMap(),anyString());
        assertThat(customerCheckout.resume(alice.getEmail(),failed.state().orderId()).redirectUrl()).isNotNull();
        assertThat(orders.count()).isEqualTo(1);assertThat(available()).isEqualTo(4);
    }
    @Test void customerConcurrentReplayReservesOnlyOnce() throws Exception {
        carts.addProductToCart(alice,product);var signed=request(alice);
        doAnswer(call->{synchronized(this){id=payments.findAll().get(0).getId();frozen=call.getArgument(0);return response(frozen);}})
            .when(gateway).create(anyMap(),anyString());
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var one=pool.submit(()->{start.await();return customerCheckout.start(alice.getEmail(),"race-customer",signed);});
            var two=pool.submit(()->{start.await();return customerCheckout.start(alice.getEmail(),"race-customer",signed);});
            start.countDown();assertThat(one.get(30,TimeUnit.SECONDS).state().orderId()).isEqualTo(two.get(30,TimeUnit.SECONDS).state().orderId());
            assertThat(orders.count()).isEqualTo(1);assertThat(payments.count()).isEqualTo(1);assertThat(available()).isEqualTo(4);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void ownershipAndMissingOrdersAreIndistinguishableAndNeverCallStripe() throws Exception {
        order();long orderId=orders.findAll().get(0).getId();clearInvocations(gateway);
        for(long target:new long[]{orderId,Long.MAX_VALUE}) {
            for(String suffix:new String[]{"","/success","/cancel","/state"})
                mvc.perform(get("/user/orders/"+target+"/payment"+suffix).with(user(bob.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(post("/user/orders/"+target+"/payment/resume").with(user(bob.getEmail())).with(csrf())).andExpect(status().isNotFound());
        }
        verifyNoInteractions(gateway);
    }
    @Test void authenticationCsrfAndReadOnlyMethodsAreEnforced() throws Exception {
        mvc.perform(post("/cart/stripe/checkout").with(csrf()).header("Idempotency-Key","key").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/cart/stripe/checkout").with(user(alice.getEmail())).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/cart/stripe/checkout").with(user(alice.getEmail()))).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/user/orders/1/payment/resume").with(user(alice.getEmail()))).andExpect(status().isForbidden());
        mvc.perform(post("/user/orders/1/payment/resume").with(user(alice.getEmail())).with(csrf().useInvalidToken()))
            .andExpect(status().isForbidden());
        for(String suffix:new String[]{"","/success","/cancel","/state"})
            mvc.perform(get("/user/orders/1/payment"+suffix))
                .andExpect(result->assertThat(result.getResponse().getStatus()).isIn(401,302));
        mvc.perform(post("/user/orders/1/payment/resume").with(csrf()))
            .andExpect(result->assertThat(result.getResponse().getStatus()).isIn(401,302));
        verifyNoInteractions(gateway);assertThat(orders.count()).isZero();
    }
    @Test void earlyAndLateReturnsOnlyReadPersistedState() throws Exception {
        order();long orderId=orders.findAll().get(0).getId();clearInvocations(gateway);
        for(String suffix:new String[]{"/success","/cancel",""})
            mvc.perform(get("/user/orders/"+orderId+"/payment"+suffix).with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("IN_ATTESA_PAGAMENTO")));
        assertThat(available()).isEqualTo(4);verify(mail,never()).confirmed(any());verifyNoInteractions(gateway);
        domain.confirm(id,new java.math.BigDecimal("18.40"),"EUR");
        mvc.perform(get("/user/orders/"+orderId+"/payment/cancel").with(user(alice.getEmail())))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("SUCCEEDED")));
        assertThat(customerCheckout.resume(alice.getEmail(),orderId).redirectUrl()).isNull();
        assertThat(available()).isEqualTo(4);verifyNoInteractions(gateway);
    }
    @Test void expiredOrTerminalAttemptsNeverCreateSessionsOrReleaseStock(){
        order();long orderId=orders.findAll().get(0).getId();clearInvocations(gateway);
        when(clock.instant()).thenReturn(Instant.parse("2030-01-02T12:00:00Z"));
        assertThat(customerCheckout.resume(alice.getEmail(),orderId).state().resumable()).isFalse();
        assertThat(available()).isEqualTo(4);verifyNoInteractions(gateway);
        domain.closeUnpaid(id,PaymentStatus.EXPIRED);
        assertThat(customerCheckout.resume(alice.getEmail(),orderId).redirectUrl()).isNull();
        assertThat(available()).isEqualTo(5);verifyNoInteractions(gateway);
    }
    @Test void areaOrdersOffersServerSideRecoveryAndPostRedirectUsesValidatedUrl() throws Exception {
        order();long orderId=orders.findAll().get(0).getId();
        mvc.perform(get("/user").with(user(alice.getEmail()))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/user/orders/"+orderId+"/payment")));
        mvc.perform(post("/user/orders/"+orderId+"/payment/resume").with(user(alice.getEmail())).with(csrf()))
            .andExpect(status().isSeeOther()).andExpect(redirectedUrl("https://checkout.stripe.com/c/test"));
    }
}
