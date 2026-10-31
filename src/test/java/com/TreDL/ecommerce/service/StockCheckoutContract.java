package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class StockCheckoutContract {
    @Autowired CustomersRepository customers;
    @Autowired ProductsRepository products;
    @Autowired OrdersRepository orders;
    @MockitoSpyBean CartRepository cartRepository;
    @Autowired CartService carts;
    @Autowired CheckoutService checkout;
    @Autowired AddressBookService addresses;
    @Autowired JdbcTemplate jdbc;
    Customers alice,bob; Products product;
    long aliceAddress,bobAddress;
    @BeforeEach void setup() {
        for(String table:List.of("order_items","orders","cart_products","cart","product_images","products","customer_addresses","customers")) jdbc.update("DELETE FROM "+table);
        alice=customer("alice");bob=customer("bob");
        product=new Products();product.setNome("Ultima esca");product.setPrezzo(new BigDecimal("12.50"));product.setStock(5);products.saveAndFlush(product);
        var input=new AddressRequest("Mario Rossi","Via Roma","1","00100","Roma","RM","IT",null,false);
        aliceAddress=addresses.create(alice.getEmail(),input).id();bobAddress=addresses.create(bob.getEmail(),input).id();
    }
    Customers customer(String name) {
        Customers c=new Customers();c.setUsername(name);c.setEmail(name+"@stock.test");c.setPassword("fixture");c.setRole("USER");return customers.saveAndFlush(c);
    }
    void stock(int quantity) {jdbc.update("UPDATE products SET stock=? WHERE id=?",quantity,product.getId());}
    int available() {return products.findById(product.getId()).orElseThrow().getStock();}
    CheckoutRequest request(Customers c) {return new CheckoutRequest(checkout.quote(c.getEmail()).quoteToken(),true,c.getId().equals(alice.getId())?aliceAddress:bobAddress,null);}
    void conflict(Runnable operation) {assertThatThrownBy(operation::run).isInstanceOfSatisfying(CheckoutException.class,e->assertThat(e.getStatus().value()).isEqualTo(409));}
    @Test void cartIsNotReservationAndCannotExceedLiveStockEvenWithStaleProduct() {
        stock(2);product.setStock(9999);
        carts.addProductToCart(alice,product);carts.addProductToCart(alice,product);
        conflict(()->carts.addProductToCart(alice,product));
        assertThat(available()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_products",Long.class)).isEqualTo(2);
        stock(0);conflict(()->carts.addProductToCart(bob,product));
        assertThat(orders.count()).isZero();
    }
    @Test void insufficientStockAfterQuoteLeavesOrderCartAndInventoryUntouched() {
        carts.addProductToCart(alice,product);carts.addProductToCart(alice,product);
        CheckoutRequest request=request(alice);stock(1);
        conflict(()->checkout.checkout(alice.getEmail(),"insufficient",request));
        assertThat(available()).isEqualTo(1);assertThat(orders.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_products",Long.class)).isEqualTo(2);
    }
    @Test void confirmedOrderConsumesOnceAndReplayWorksWhenSoldOut() {
        stock(1);carts.addProductToCart(alice,product);CheckoutRequest request=request(alice);
        OrderDTO first=checkout.checkout(alice.getEmail(),"once",request);
        assertThat(available()).isZero();assertThat(checkout.checkout(alice.getEmail(),"once",request)).isEqualTo(first);
        assertThat(available()).isZero();assertThat(orders.count()).isEqualTo(1);
    }
    @Test void errorAfterOrderFlushRollsBackStockOrderAndCartAndRetryCanSucceed() {
        carts.addProductToCart(alice,product);CheckoutRequest request=request(alice);
        doThrow(new DataIntegrityViolationException("forced cart failure")).when(cartRepository).saveAndFlush(any(Cart.class));
        try {assertThatThrownBy(()->checkout.checkout(alice.getEmail(),"rollback",request)).isInstanceOf(DataIntegrityViolationException.class);}
        finally {reset(cartRepository);}
        assertThat(available()).isEqualTo(5);assertThat(orders.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_products",Long.class)).isEqualTo(1);
        checkout.checkout(alice.getEmail(),"rollback",request);assertThat(available()).isEqualTo(4);
    }
    @Test void twoCustomersBuyingLastUnitCreateExactlyOneOrder() throws Exception {
        stock(1);carts.addProductToCart(alice,product);carts.addProductToCart(bob,product);
        CheckoutRequest a=request(alice),b=request(bob);
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            var one=workers.submit(()->attempt(alice,a,ready,start));var two=workers.submit(()->attempt(bob,b,ready,start));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(available()).isZero();assertThat(orders.count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_products",Long.class)).isEqualTo(1);
        } finally {start.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    int attempt(Customers customer,CheckoutRequest request,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("race not released");
        try {checkout.checkout(customer.getEmail(),"last-unit",request);return 200;}
        catch(CheckoutException e){return e.getStatus().value();}
    }
}
