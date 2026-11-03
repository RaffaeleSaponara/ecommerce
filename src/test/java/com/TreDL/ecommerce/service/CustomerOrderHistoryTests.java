package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Products;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CustomerOrderHistoryTests extends StockCheckoutContract {
    @Autowired OrderHistoryService history;
    @Autowired ShippingService shipping;
    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory factory;

    long purchase(String key) {
        carts.addProductToCart(alice,product);
        return checkout.checkout(alice.getEmail(),key,request(alice)).id();
    }

    @Test void multipleItemsKeepHistoricalNamesPricesQuantitiesAndTotals() throws Exception {
        Products second=new Products();second.setNome("Esca speciale <script>");
        second.setPrezzo(new BigDecimal("3.00"));second.setStock(5);products.saveAndFlush(second);
        carts.addProductToCart(alice,product);carts.addProductToCart(alice,product);
        carts.addProductToCart(alice,second);
        var saved=checkout.checkout(alice.getEmail(),"items",request(alice));
        jdbc.update("UPDATE products SET prezzo=99.00, nome='Nuovo nome' WHERE id=?",product.getId());
        var summary=history.forCustomer(alice.getEmail()).get(0);
        assertThat(summary.items()).hasSize(2);
        var first=summary.items().get(0);
        assertThat(first.productName()).isEqualTo("Ultima esca");
        assertThat(first.quantity()).isEqualTo(2);
        assertThat(first.unitPrice()).isEqualByComparingTo("12.50");
        assertThat(first.subtotal()).isEqualByComparingTo("25.00");
        assertThat(summary.items().get(1).subtotal()).isEqualByComparingTo("3.00");
        assertThat(summary.subtotal()).isEqualByComparingTo("28.00");
        assertThat(summary.total()).isEqualByComparingTo(saved.total());
        assertThat(summary.shippingAddress()).isNotNull();assertThat(summary.billingAddress()).isNotNull();
        mvc.perform(get("/user").with(user(alice.getEmail())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Totale riga:")))
            .andExpect(content().string(containsString("25,00 €")))
            .andExpect(content().string(containsString("Esca speciale &lt;script&gt;")))
            .andExpect(content().string(not(containsString("Nuovo nome"))));
    }

    @Test void identityComesOnlyFromSessionAndUnknownIdsExposeNothing() throws Exception {
        long id=purchase("private");
        shipping.tracking(id,new ShippingService.Tracking("Corriere privato","SECRET-TRACK"));
        mvc.perform(get("/user").with(user(bob.getEmail())).param("orderId",Long.toString(id)).param("customerId",alice.getId().toString()))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Non hai ancora effettuato ordini")))
            .andExpect(content().string(not(containsString("SECRET-TRACK"))))
            .andExpect(content().string(not(containsString("Ultima esca"))));
        mvc.perform(get("/user").with(user(bob.getEmail())).param("orderId","9223372036854775807"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Non hai ancora effettuato ordini")));
        mvc.perform(get("/user")).andExpect(status().isUnauthorized());
    }

    @Test void trackingIsShownOnlyWhenAvailable() throws Exception {
        long id=purchase("tracking");
        mvc.perform(get("/user").with(user(alice.getEmail())))
            .andExpect(status().isOk()).andExpect(content().string(not(containsString("Codice di tracciamento:"))));
        shipping.tracking(id,new ShippingService.Tracking("Corriere test","TRACK-OK"));
        mvc.perform(get("/user").with(user(alice.getEmail())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Corriere test")))
            .andExpect(content().string(containsString("TRACK-OK")));
    }

    @Test void linesAndStatusHistoryUseTwoQueriesForMultipleOrders() {
        long first=purchase("first");long second=purchase("second");
        var statistics=factory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled=statistics.isStatisticsEnabled();statistics.setStatisticsEnabled(true);statistics.clear();
        try {
            var summaries=history.forCustomer(alice.getEmail());
            assertThat(summaries).extracting(OrderHistoryService.Summary::id).containsExactly(second,first);
            assertThat(summaries).allSatisfy(s->assertThat(s.items()).hasSize(1));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        } finally { statistics.setStatisticsEnabled(enabled); }
    }
}
