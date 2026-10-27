package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring transactions, sessions, repositories and HTTP against the unchanged V1. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CheckoutIntegrationContract.TimeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class CheckoutIntegrationContract {
    static class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-27T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean TestClock checkoutTestClock() { return new TestClock(); }
        @Bean @Primary QuoteSigner testQuoteSigner(TestClock clock) {
            return new QuoteSigner("dGVzdC1vbmx5LWNoZWNrb3V0LWtleS0zMi1ieXRlcyEhISE=", clock);
        }
    }
    @Autowired CheckoutService checkout;
    @Autowired CartService cartService;
    @Autowired CustomersRepository customers;
    @Autowired ProductsRepository products;
    @MockitoSpyBean OrdersRepository orders;
    @MockitoSpyBean CartRepository carts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoSpyBean(name = "testQuoteSigner") QuoteSigner signer;
    TransactionTemplate tx;
    Customers alice, bob;
    Products first, second;

    @BeforeEach void fixtures() {
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        clock.now = Instant.parse("2026-10-27T12:00:00Z");
        tx.executeWithoutResult(status -> {
            for (String table : List.of("order_items","orders","cart_products","cart","products","customers"))
                jdbc.update("DELETE FROM " + table);
            alice = customer("alice"); bob = customer("bob");
            first = product("First", "0.10"); second = product("Second", "0.20");
        });
        cartService.addProductToCart(alice, second);
        cartService.addProductToCart(alice, first);
        cartService.addProductToCart(alice, first);
    }
    private Customers customer(String name) {
        Customers c = new Customers(); c.setUsername(name); c.setEmail(name+"@example.com");
        c.setPassword("test-only-password"); c.setRole("USER"); return customers.saveAndFlush(c);
    }
    private Products product(String name, String price) {
        Products p = new Products(); p.setNome(name); p.setPrezzo(new BigDecimal(price)); return products.saveAndFlush(p);
    }
    protected CheckoutRequest confirm(QuoteDTO quote) { return new CheckoutRequest(quote.quoteToken(), true); }
    protected List<Long> cartIds() {
        return tx.execute(status -> carts.findByCustomerId(alice.getId()).orElseThrow().getProducts()
                .stream().map(Products::getId).sorted().toList());
    }
    protected void intact() {
        assertThat(orders.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items",Long.class)).isZero();
        assertThat(cartIds()).containsExactly(first.getId(), first.getId(), second.getId());
    }
    protected void rejected(Runnable operation, int status) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(CheckoutException.class,
                error -> assertThat(error.getStatus().value()).isEqualTo(status));
    }

    @Test void createsAtomicOrderWithAggregatedQuantitiesExactTotalsAndHistoricSnapshots() {
        QuoteDTO quote = checkout.quote(alice.getEmail());
        OrderDTO result = checkout.checkout(alice.getEmail(), "success", confirm(quote));
        assertThat(result.id()).isNotNull(); assertThat(result.total()).isEqualByComparingTo("0.40");
        assertThat(result.currency()).isEqualTo("EUR"); assertThat(cartIds()).isEmpty();
        assertThat(result.items()).extracting(OrderItemDTO::lineNumber).containsExactly(1,2);
        assertThat(result.items()).extracting(OrderItemDTO::quantity).containsExactly(2,1);
        assertThat(result.items()).extracting(OrderItemDTO::subtotal).containsExactly(new BigDecimal("0.20"),new BigDecimal("0.20"));
        tx.executeWithoutResult(status -> {
            Products changed = products.findById(first.getId()).orElseThrow();
            changed.setNome("Renamed"); changed.setPrezzo(new BigDecimal("999.99"));
        });
        OrderDTO replay = checkout.checkout(alice.getEmail(), "success", confirm(quote));
        assertThat(replay).isEqualTo(result);
        assertThat(replay.items().get(0).productName()).isEqualTo("First");
        assertThat(replay.items().get(0).unitPrice()).isEqualTo(new BigDecimal("0.10"));
    }

    @Test void replaySurvivesExpiryAndLeavesNewCartUntouched() {
        QuoteDTO quote = checkout.quote(alice.getEmail());
        OrderDTO original = checkout.checkout(alice.getEmail(), "retry", confirm(quote));
        cartService.addProductToCart(alice, second);
        clock.now = clock.now.plusSeconds(600);
        assertThat(checkout.checkout(alice.getEmail(), "retry", confirm(quote))).isEqualTo(original);
        assertThat(cartIds()).containsExactly(second.getId()); assertThat(orders.count()).isEqualTo(1);
    }

    @Test void sameKeyWithDifferentCanonicalRequestConflictsWithoutConsumingNewCart() {
        var firstQuote = checkout.quote(alice.getEmail());
        checkout.checkout(alice.getEmail(), "same-key", confirm(firstQuote));
        cartService.addProductToCart(alice, second);
        var secondQuote = checkout.quote(alice.getEmail());
        rejected(() -> checkout.checkout(alice.getEmail(), "same-key", confirm(secondQuote)),409);
        assertThat(orders.count()).isEqualTo(1); assertThat(cartIds()).containsExactly(second.getId());
    }

    @Test void keyIsCaseSensitiveAndScopedToCustomer() {
        var quote = checkout.quote(alice.getEmail());
        checkout.checkout(alice.getEmail(), "Key", confirm(quote));
        cartService.addProductToCart(alice, first);
        checkout.checkout(alice.getEmail(), "key", confirm(checkout.quote(alice.getEmail())));
        cartService.addProductToCart(bob, first);
        checkout.checkout(bob.getEmail(), "Key", confirm(checkout.quote(bob.getEmail())));
        assertThat(orders.count()).isEqualTo(3);
    }

    @Test void saveFailureRollsBackAndPreservesCartAndReusableKey() {
        var quote = checkout.quote(alice.getEmail());
        doThrow(new DataIntegrityViolationException("injected order write failure")).when(orders).saveAndFlush(any(Orders.class));
        assertThatThrownBy(() -> checkout.checkout(alice.getEmail(), "failed", confirm(quote)))
                .isInstanceOf(DataIntegrityViolationException.class);
        intact(); reset(orders);
        assertThat(checkout.checkout(alice.getEmail(), "failed", confirm(quote)).total()).isEqualTo(new BigDecimal("0.40"));
    }

    @Test void failureAfterOrderRowsWereFlushedRollsBackEverything() {
        var quote = checkout.quote(alice.getEmail());
        doThrow(new DataIntegrityViolationException("injected cart write failure")).when(carts).saveAndFlush(any(Cart.class));
        assertThatThrownBy(() -> checkout.checkout(alice.getEmail(), "failed-late", confirm(quote)))
                .isInstanceOf(DataIntegrityViolationException.class);
        intact(); reset(carts);
        checkout.checkout(alice.getEmail(), "failed-late", confirm(quote));
        assertThat(orders.count()).isEqualTo(1); assertThat(cartIds()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"price","name","quantity","expired","tampered","foreign","unconfirmed","missing"})
    void rejectsInvalidConfirmationWithoutCreatingOrders(String kind) {
        var quote = checkout.quote(alice.getEmail());
        CheckoutRequest request = confirm(quote);
        int status = 409;
        switch (kind) {
            case "price" -> tx.executeWithoutResult(s -> products.findById(first.getId()).orElseThrow().setPrezzo(new BigDecimal("0.11")));
            case "name" -> tx.executeWithoutResult(s -> products.findById(first.getId()).orElseThrow().setNome("Changed"));
            case "quantity" -> cartService.addProductToCart(alice, first);
            case "expired" -> clock.now = clock.now.plusSeconds(301);
            case "tampered" -> { request = new CheckoutRequest("x" + quote.quoteToken(),true); status = 400; }
            case "foreign" -> {
                cartService.addProductToCart(bob, first);
                request = confirm(checkout.quote(bob.getEmail())); status = 400;
            }
            case "unconfirmed" -> { request = new CheckoutRequest(quote.quoteToken(), false); status = 400; }
            case "missing" -> { request = new CheckoutRequest(null,true); status = 400; }
        }
        CheckoutRequest submitted = request;
        rejected(() -> checkout.checkout(alice.getEmail(), "invalid", submitted),status);
        assertThat(orders.count()).isZero();
        assertThat(cartIds()).hasSize(kind.equals("quantity") ? 4 : 3);
    }

    @Test void overflowRejectsWholeOrder() {
        tx.executeWithoutResult(s -> products.findById(first.getId()).orElseThrow().setPrezzo(new BigDecimal("99999999999999999.99")));
        assertThatThrownBy(() -> checkout.quote(alice.getEmail())).isInstanceOf(IllegalArgumentException.class);
        intact();
    }

    @Test void realHttpFlowRequiresSessionCsrfKeyAndExplicitSignedConfirmation() throws Exception {
        mvc.perform(post("/cart/quote").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/cart/quote").with(user(alice.getEmail()))).andExpect(status().isForbidden());
        String json = mvc.perform(post("/cart/quote").with(user(alice.getEmail())).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.items[0].unitPrice").isNumber())
                .andReturn().getResponse().getContentAsString();
        QuoteDTO quote = mapper.readValue(json,QuoteDTO.class);
        String body = mapper.writeValueAsString(confirm(quote));
        mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).with(csrf())
                .header("Idempotency-Key","http").contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmPrices\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).with(csrf())
                .header("Idempotency-Key","http").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteToken\":\"unsigned\",\"confirmPrices\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).with(csrf())
                .header("Idempotency-Key","http").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new CheckoutRequest(quote.quoteToken(),false))))
                .andExpect(status().isBadRequest());
        intact();
        mvc.perform(post("/cart/checkout").with(csrf()).header("Idempotency-Key","http")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).header("Idempotency-Key","http")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String response = mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).with(csrf())
                .header("Idempotency-Key","http").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.total").isNumber()).andExpect(jsonPath("$.total").value(0.40))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("password","customer","requestHash","idempotencyKey","hibernateLazyInitializer");
        mvc.perform(post("/cart/checkout").with(user(alice.getEmail())).with(csrf())
                .header("Idempotency-Key","http").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().json(response));
        assertThat(orders.count()).isEqualTo(1); assertThat(cartIds()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"same-key","different-key","add","remove","clear"})
    void concurrentCheckoutSerializesWithCheckoutAndCartMutations(String operation) throws Exception {
        var quote = checkout.quote(alice.getEmail());
        CountDownLatch hasLock = new CountDownLatch(1), release = new CountDownLatch(1), waiting = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<OrderDTO> firstRequest = workers.submit(() -> tx.execute(s -> {
                OrderDTO result = checkout.checkout(alice.getEmail(),"concurrent",confirm(quote));
                hasLock.countDown(); await(release); return result;
            }));
            assertThat(hasLock.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> next = workers.submit(() -> {
                waiting.countDown();
                switch (operation) {
                    case "same-key" -> { return checkout.checkout(alice.getEmail(),"concurrent",confirm(quote)); }
                    case "different-key" -> {
                        rejected(() -> checkout.checkout(alice.getEmail(),"second",confirm(quote)),409);
                    }
                    case "add" -> cartService.addProductToCart(alice,first);
                    case "remove" -> cartService.removeProductToCart(alice,first);
                    case "clear" -> cartService.clearCart(alice.getId());
                }
                return null;
            });
            assertThat(waiting.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> next.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            OrderDTO original = firstRequest.get(10,TimeUnit.SECONDS);
            Object secondResult = next.get(10,TimeUnit.SECONDS);
            if (operation.equals("same-key")) assertThat(secondResult).isEqualTo(original);
            assertThat(orders.count()).isEqualTo(1);
            assertThat(cartIds()).hasSize(operation.equals("add") ? 1 : 0);
        } finally { release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void cartMutationCommittingFirstInvalidatesQuoteForWaitingCheckout() throws Exception {
        var quote = checkout.quote(alice.getEmail());
        CountDownLatch hasLock = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> mutation = workers.submit(() -> tx.executeWithoutResult(s -> {
                cartService.addProductToCart(alice,first); hasLock.countDown(); await(release);
            }));
            assertThat(hasLock.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> checkoutRequest = workers.submit(() -> rejected(
                    () -> checkout.checkout(alice.getEmail(),"after-mutation",confirm(quote)),409));
            assertThatThrownBy(() -> checkoutRequest.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); mutation.get(10,TimeUnit.SECONDS); checkoutRequest.get(10,TimeUnit.SECONDS);
            assertThat(orders.count()).isZero(); assertThat(cartIds()).hasSize(4);
        } finally { release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void concurrentRetryCanCommitAfterFirstCheckoutRollsBack() throws Exception {
        var quote = checkout.quote(alice.getEmail());
        CountDownLatch hasLock = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> failed = workers.submit(() -> tx.executeWithoutResult(status -> {
                checkout.checkout(alice.getEmail(), "rollback-race", confirm(quote));
                hasLock.countDown(); await(release); status.setRollbackOnly();
            }));
            assertThat(hasLock.await(10,TimeUnit.SECONDS)).isTrue();
            Future<OrderDTO> retry = workers.submit(() -> checkout.checkout(alice.getEmail(),"rollback-race",confirm(quote)));
            assertThatThrownBy(() -> retry.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); failed.get(10,TimeUnit.SECONDS);
            assertThat(retry.get(10,TimeUnit.SECONDS).total()).isEqualTo(new BigDecimal("0.40"));
            assertThat(orders.count()).isEqualTo(1); assertThat(cartIds()).isEmpty();
        } finally { release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void isolatedSpringVerificationDoesNotRegisterDataInitializer() {
        assertThat(context.getBeansOfType(com.TreDL.ecommerce.DataInitializer.class)).isEmpty();
    }

    @Test void missingSecretRejectsNewCheckoutWithoutChangesButAllowsPersistedReplay() {
        var quote = checkout.quote(alice.getEmail());
        CheckoutService unavailable = new CheckoutService(customers, carts, orders, new QuoteSigner("", clock));
        rejected(() -> tx.execute(status -> unavailable.checkout(alice.getEmail(), "no-secret", confirm(quote))), 503);
        intact();
        OrderDTO original = checkout.checkout(alice.getEmail(), "no-secret", confirm(quote));
        assertThat(tx.<OrderDTO>execute(status -> unavailable.checkout(alice.getEmail(), "no-secret", confirm(quote))))
                .isEqualTo(original);
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test void removedCatalogProductCannotBeOrderedFromAnOldQuote() {
        var quote = checkout.quote(alice.getEmail());
        cartService.removeProductToCart(alice, first);
        cartService.removeProductToCart(alice, first);
        tx.executeWithoutResult(status -> products.deleteById(first.getId()));
        rejected(() -> checkout.checkout(alice.getEmail(), "removed", confirm(quote)), 409);
        assertThat(orders.count()).isZero();
        assertThat(cartIds()).containsExactly(second.getId());
    }

    @ParameterizedTest @ValueSource(strings = {"price", "expiry"})
    void checkoutRechecksCatalogAndExpiryAfterWaitingForProductWriter(String change) throws Exception {
        var quote = checkout.quote(alice.getEmail());
        CountDownLatch productLocked = new CountDownLatch(1), release = new CountDownLatch(1), verified = new CountDownLatch(1);
        doAnswer(invocation -> {
            QuoteSigner.Payload payload = (QuoteSigner.Payload) invocation.callRealMethod();
            verified.countDown(); return payload;
        }).when(signer).verify(anyString(), anyLong());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = workers.submit(() -> tx.executeWithoutResult(status -> {
                // Direct catalog writer: the customer lock cannot serialize this transaction.
                jdbc.queryForList("SELECT id FROM products WHERE id = ? FOR UPDATE", first.getId());
                if (change.equals("price")) jdbc.update("UPDATE products SET prezzo = 0.11 WHERE id = ?", first.getId());
                productLocked.countDown(); await(release);
            }));
            assertThat(productLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> attempt = workers.submit(() -> rejected(
                    () -> checkout.checkout(alice.getEmail(), "catalog-wait", confirm(quote)), 409));
            assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> attempt.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            if (change.equals("expiry")) clock.now = clock.now.plusSeconds(301);
            release.countDown(); writer.get(10, TimeUnit.SECONDS); attempt.get(10, TimeUnit.SECONDS);
            intact();
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void checkoutHoldsCatalogPriceLocksUntilCommit() throws Exception {
        var quote = checkout.quote(alice.getEmail());
        CountDownLatch created = new CountDownLatch(1), release = new CountDownLatch(1), writerStarted = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<OrderDTO> attempt = workers.submit(() -> tx.execute(status -> {
                OrderDTO order = checkout.checkout(alice.getEmail(), "catalog-stable", confirm(quote));
                created.countDown(); await(release); return order;
            }));
            assertThat(created.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> writer = workers.submit(() -> tx.executeWithoutResult(status -> {
                writerStarted.countDown();
                jdbc.update("UPDATE products SET prezzo = 9.99, nome = 'Changed' WHERE id = ?", first.getId());
            }));
            assertThat(writerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> writer.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); OrderDTO original = attempt.get(10, TimeUnit.SECONDS); writer.get(10, TimeUnit.SECONDS);
            assertThat(original.total()).isEqualTo(new BigDecimal("0.40"));
            assertThat(checkout.checkout(alice.getEmail(), "catalog-stable", confirm(quote))).isEqualTo(original);
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void concurrentSameKeyWithDifferentConfirmedPayloadConflicts() throws Exception {
        var quote = checkout.quote(alice.getEmail());
        clock.now = clock.now.plusSeconds(1);
        var differentQuote = checkout.quote(alice.getEmail());
        CountDownLatch created = new CountDownLatch(1), release = new CountDownLatch(1), waiting = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstAttempt = workers.submit(() -> tx.executeWithoutResult(status -> {
                checkout.checkout(alice.getEmail(), "payload-race", confirm(quote));
                created.countDown(); await(release);
            }));
            assertThat(created.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> differentAttempt = workers.submit(() -> {
                waiting.countDown();
                rejected(() -> checkout.checkout(alice.getEmail(), "payload-race", confirm(differentQuote)), 409);
            });
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> differentAttempt.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); firstAttempt.get(10, TimeUnit.SECONDS); differentAttempt.get(10, TimeUnit.SECONDS);
            assertThat(orders.count()).isEqualTo(1); assertThat(cartIds()).isEmpty();
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
