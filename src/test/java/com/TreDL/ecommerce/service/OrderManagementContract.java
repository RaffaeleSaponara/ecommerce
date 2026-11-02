package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
abstract class OrderManagementContract extends StockCheckoutContract {
    @Autowired OrderManagementService management;
    @Autowired MockMvc mvc;
    OrderDTO confirmed() {
        carts.addProductToCart(alice,product);
        return checkout.checkout(alice.getEmail(),"order",request(alice));
    }
    void rejected(long id,String next) {
        assertThatThrownBy(()->management.update(id,next)).isInstanceOfSatisfying(ResponseStatusException.class,
            error->assertThat(error.getStatusCode().value()).isEqualTo(409));
    }
    @Test void legalTransitionsAndHistoryWithoutRetrospectiveRecords() {
        long id=confirmed().id();
        assertThat(management.update(id,"CONFERMATO").statusHistory()).isEmpty();
        rejected(id,"SPEDITO");
        assertThat(management.update(id,"IN_PREPARAZIONE").shipment()).isEqualTo("IN_PREPARAZIONE");
        assertThat(management.update(id,"SPEDITO").shipment()).isEqualTo("SPEDITO");
        rejected(id,"ANNULLATO");
        var delivered=management.update(id,"CONSEGNATO");
        assertThat(delivered.statusHistory()).extracting(OrderDTO.StatusChange::newStatus)
            .containsExactly("IN_PREPARAZIONE","SPEDITO","CONSEGNATO");
        rejected(id,"ANNULLATO"); rejected(id,"CONFERMATO");
        assertThat(available()).isEqualTo(4);
        assertThatThrownBy(()->management.update(id,"unknown")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancellationRestoresExactlyOnceAndCheckoutReplayDoesNotConsumeAgain() {
        carts.addProductToCart(alice,product); CheckoutRequest request=request(alice);
        var original=checkout.checkout(alice.getEmail(),"order",request);
        management.update(original.id(),"IN_PREPARAZIONE");
        var cancelled=management.update(original.id(),"ANNULLATO");
        assertThat(available()).isEqualTo(5);
        assertThat(management.update(original.id(),"ANNULLATO")).isEqualTo(cancelled);
        assertThat(checkout.checkout(alice.getEmail(),"order",request).shipment()).isEqualTo("ANNULLATO");
        assertThat(available()).isEqualTo(5);
        assertThat(orders.sumConfirmedOrderTotals()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_changes",Long.class)).isEqualTo(2);
        rejected(original.id(),"CONFERMATO");
    }
    @Test void historicalOrdersAreTreatedAsAlreadyDeductedAndLegacyStatusCanAdvance() {
        long id=confirmed().id();
        jdbc.update("UPDATE orders SET ship_status='In elaborazione' WHERE id=?",id);
        assertThat(available()).isEqualTo(4);
        assertThat(management.update(id,"IN_PREPARAZIONE").shipment()).isEqualTo("IN_PREPARAZIONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_changes",Long.class)).isEqualTo(1);
        management.update(id,"ANNULLATO");
        assertThat(available()).isEqualTo(5);
    }
    @Test void overflowRollsBackCancellationAndHistory() {
        Products second=new Products();second.setNome("Seconda esca");second.setPrezzo(new java.math.BigDecimal("2.00"));second.setStock(1);products.saveAndFlush(second);
        carts.addProductToCart(alice,second);
        long id=confirmed().id();
        jdbc.update("UPDATE products SET stock=? WHERE id=?",Integer.MAX_VALUE,second.getId());
        rejected(id,"ANNULLATO");
        // The first product was restored in memory before the second overflowed.
        // Both persisted stock and order state must still reflect the original checkout.
        assertThat(available()).isEqualTo(4);
        assertThat(products.findById(second.getId()).orElseThrow().getStock()).isEqualTo(Integer.MAX_VALUE);
        assertThat(orders.findById(id).orElseThrow().getShipment()).isEqualTo("CONFERMATO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_changes",Long.class)).isZero();
    }
    @Test void statusWritesRequireAdminAuthenticationAndCsrfAndCustomerSeesUpdatedStatus() throws Exception {
        long id=confirmed().id();String url="/api/admin/orders/"+id+"/status";
        String body="{\"status\":\"ANNULLATO\"}";
        mvc.perform(post(url).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(user(alice.getEmail()).roles("USER")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("admin").roles("ADMIN")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"status\":\"wrong\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.shipment").value("ANNULLATO"));
        mvc.perform(get("/user").with(user(alice.getEmail()))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ANNULLATO")));
        assertThat(available()).isEqualTo(5);
    }
    @Test void simultaneousCancellationRestoresOnlyOnce() throws Exception {
        long id=confirmed().id();
        ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        Callable<String> cancel=()-> {ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("race not released");return management.update(id,"ANNULLATO").shipment();};
        try {
            var first=workers.submit(cancel);var second=workers.submit(cancel);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsOnly("ANNULLATO");
            assertThat(available()).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_changes",Long.class)).isEqualTo(1);
        } finally {start.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
