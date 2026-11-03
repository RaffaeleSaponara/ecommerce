package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ProductArchivingTests extends StockCheckoutContract {
    @Autowired AdminService admin;
    @Autowired OrderHistoryService history;
    @Autowired ProductImagesService images;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;

    @Test void defaultActiveArchiveHideAndReactivateIncludingZeroStock() throws Exception {
        var created=admin.create(new AdminProductRequest("Nuova esca",null,new BigDecimal("4.00"),null,null));
        assertThat(created.isActive()).isTrue();
        admin.publication(product.getId(),false);
        mvc.perform(get("/prodotti/all")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/prodotti/all").param("q","Ultima").param("minPrice","1")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/products/"+product.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/prodotti/"+product.getId())).andExpect(status().isNotFound());
        assertThat(admin.products()).hasSize(2);
        assertThat(images.list(product.getId())).isEmpty();
        admin.update(product.getId(),new AdminProductRequest("Esistente",null,new BigDecimal("12.50"),null,null,BigDecimal.ZERO));
        assertThat(products.findById(product.getId()).orElseThrow().isActive()).isFalse();
        admin.publication(product.getId(),true);
        mvc.perform(get("/api/products/"+product.getId())).andExpect(status().isOk());
        mvc.perform(get("/prodotti/all")).andExpect(jsonPath("$.length()").value(2));
        conflict(()->carts.addProductToCart(alice,product));
    }

    @Test void archivedCartAndPreviouslySignedQuoteAreRejectedButRemovalWorks() {
        carts.addProductToCart(alice,product);var signed=request(alice);
        admin.publication(product.getId(),false);
        conflict(()->carts.addProductToCart(alice,product));
        assertThatThrownBy(()->checkout.quote(alice.getEmail())).hasMessageContaining("archiviato");
        conflict(()->checkout.checkout(alice.getEmail(),"archived",signed));
        assertThat(orders.count()).isZero();assertThat(available()).isEqualTo(5);
        assertThat(carts.getCartForView(alice).getProducts()).hasSize(1);
        carts.removeProductToCart(alice,product);
        assertThat(carts.getCartForView(alice).getProducts()).isEmpty();
    }

    @Test void historyAndIdempotentReplayRemainUnchangedAfterArchiving() {
        carts.addProductToCart(alice,product);var signed=request(alice);
        var order=checkout.checkout(alice.getEmail(),"historical",signed);
        admin.publication(product.getId(),false);
        assertThat(checkout.checkout(alice.getEmail(),"historical",signed)).isEqualTo(order);
        assertThat(admin.order(order.id())).isEqualTo(order);
        var line=history.forCustomer(alice.getEmail()).get(0).items().get(0);
        assertThat(line.productName()).isEqualTo("Ultima esca");
        assertThat(line.unitPrice()).isEqualByComparingTo("12.50");
        assertThat(available()).isEqualTo(4);
        admin.publication(product.getId(),true);
        assertThat(admin.order(order.id())).isEqualTo(order);
    }

    @Test void publicationRequiresAdminAndCsrf() throws Exception {
        String url="/api/admin/products/"+product.getId()+"/publication";
        mvc.perform(put(url).contentType("application/json").content("{\"active\":false}").with(csrf()).with(user(alice.getEmail())))
            .andExpect(status().isForbidden());
        mvc.perform(put(url).contentType("application/json").content("{\"active\":false}").with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(put(url).contentType("application/json").content("{}").with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
        mvc.perform(put(url).contentType("application/json").content("{\"active\":false}").with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test void checkoutWaitsForConcurrentArchivingThenRejectsWithoutDeductingStock() throws Exception {
        carts.addProductToCart(alice,product);var signed=request(alice);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> archive=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                admin.publication(product.getId(),false);locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Release timeout"); }
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> purchase=pool.submit(()->{started.countDown();conflict(()->checkout.checkout(alice.getEmail(),"race",signed));});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->purchase.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();archive.get(10,TimeUnit.SECONDS);purchase.get(10,TimeUnit.SECONDS);
            assertThat(orders.count()).isZero();assertThat(available()).isEqualTo(5);
        } finally {release.countDown();pool.shutdownNow();}
    }
}
