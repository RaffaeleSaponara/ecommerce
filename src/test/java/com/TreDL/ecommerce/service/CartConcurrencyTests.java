package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.CartRepository;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.repository.ProductsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CartService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CartConcurrencyTests {
    @Autowired private CartService service;
    @Autowired private CartRepository carts;
    @Autowired private CustomersRepository customers;
    @Autowired private ProductsRepository products;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private TransactionTemplate transactions;
    private ExecutorService workers;
    private Customers customer;
    private Customers otherCustomer;
    private Products product;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        workers = Executors.newFixedThreadPool(3);
        transactions.executeWithoutResult(status -> {
            customer = newCustomer("concurrent");
            otherCustomer = newCustomer("independent");
            Products item = new Products();
            item.setNome("Concurrent product");
            item.setPrezzo(10.0);
            product = products.saveAndFlush(item);
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        transactions.executeWithoutResult(status -> {
            carts.deleteAll();
            customers.deleteAll();
            products.deleteAll();
        });
    }

    @ParameterizedTest(name = "{0} then {1}: {2} occurrences")
    @CsvSource({
            "create, create, 0", "add, add, 2", "add, remove, 0",
            "remove, add, 1", "clear, add, 1", "add, clear, 0",
            "create, clear, 0", "clear, create, 0"
    })
    void serializesSameCustomerAndAllowsAnotherCustomerToCommit(
            String firstOperation, String secondOperation, int expectedCount) throws Exception {
        if (firstOperation.equals("remove") || firstOperation.equals("clear")) {
            service.addProductToCart(customer, product);
        }
        runContended(firstOperation, secondOperation, false);

        assertThat(productIds(customer)).hasSize(expectedCount).allMatch(product.getId()::equals);
        assertThat(transactions.<Long>execute(status -> carts.count())).isEqualTo(2L);
        assertThat(productIds(otherCustomer)).containsExactly(product.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "add", "remove", "clear"})
    void rollbackReleasesLockAndDiscardsAllCartChanges(String operation) throws Exception {
        boolean existing = operation.equals("remove") || operation.equals("clear");
        if (existing) {
            service.addProductToCart(customer, product);
            service.addProductToCart(customer, product);
        }
        runContended(operation, "add", true);

        // The waiting transaction sees only committed contents, including when
        // the rolled-back transaction had inserted the very first cart.
        assertThat(productIds(customer)).hasSize(existing ? 3 : 1).containsOnly(product.getId());
        assertThat(transactions.<Long>execute(status -> carts.count())).isEqualTo(2L);
        assertThat(productIds(otherCustomer)).containsExactly(product.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void viewPerformsNoWritesAndDoesNotHoldExclusiveLock(boolean exists) throws Exception {
        if (exists) {
            service.addProductToCart(customer, product);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var view = service.getCartForView(customer);
            assertThat(Hibernate.isInitialized(view.getProducts())).isTrue();
            assertThat(view.getProducts()).hasSize(exists ? 1 : 0);
            assertThat(statistics.getEntityInsertCount()).isZero();
            assertThat(statistics.getEntityUpdateCount()).isZero();
            assertThat(statistics.getEntityDeleteCount()).isZero();
            assertThat(statistics.getCollectionRecreateCount()).isZero();
            assertThat(statistics.getCollectionUpdateCount()).isZero();
            assertThat(statistics.getCollectionRemoveCount()).isZero();
            assertThat(carts.count()).isEqualTo(exists ? 1 : 0);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }

        CountDownLatch viewRead = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicReference<Object> readerSession = new AtomicReference<>();
        AtomicReference<Object> writerSession = new AtomicReference<>();
        TransactionTemplate readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setReadOnly(true);
        readTransaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        Future<?> reader = workers.submit(() -> readTransaction.executeWithoutResult(status -> {
            readerSession.set(sessionId());
            service.getCartForView(customer);
            viewRead.countDown();
            await(releaseReader);
        }));
        try {
            assertThat(viewRead.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> writer = workers.submit(() -> transactions.executeWithoutResult(status -> {
                writerSession.set(sessionId());
                service.addProductToCart(customer, product);
            }));
            // An exclusive customer lock held by the reader would block this commit.
            writer.get(5, TimeUnit.SECONDS);
            assertThat(writerSession.get()).isNotEqualTo(readerSession.get());
            assertThat(reader.isDone()).isFalse();
            assertThat(productIds(customer)).hasSize(exists ? 2 : 1).containsOnly(product.getId());
        } finally {
            releaseReader.countDown();
            reader.get(10, TimeUnit.SECONDS);
        }
    }

    private void runContended(String firstOperation, String secondOperation, boolean rollback)
            throws Exception {
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondTransactionStarted = new CountDownLatch(1);
        AtomicReference<Object> firstSession = new AtomicReference<>();
        AtomicReference<Object> secondSession = new AtomicReference<>();
        Future<?> first = workers.submit(() -> transactions.executeWithoutResult(status -> {
            firstSession.set(sessionId());
            perform(firstOperation, customer);
            // Flush makes rollback assertions cover real SQL, not just dirty state.
            entityManager.flush();
            firstHasLock.countDown();
            await(releaseFirst);
            if (rollback) {
                status.setRollbackOnly();
            }
        }));
        try {
            assertThat(firstHasLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = workers.submit(() -> transactions.executeWithoutResult(status -> {
                secondSession.set(sessionId());
                secondTransactionStarted.countDown();
                perform(secondOperation, customer);
            }));
            assertThat(secondTransactionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(firstSession.get()).isNotEqualTo(secondSession.get());
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // This must complete before releasing the first customer's lock.
            Future<?> independent = workers.submit(() -> service.addProductToCart(otherCustomer, product));
            independent.get(10, TimeUnit.SECONDS);
            assertThat(productIds(otherCustomer)).containsExactly(product.getId());
            assertThat(second.isDone()).isFalse();

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
        }
    }

    private void perform(String operation, Customers owner) {
        switch (operation) {
            case "create" -> service.getCart(owner);
            case "add" -> service.addProductToCart(owner, product);
            case "remove" -> service.removeProductToCart(owner, product);
            case "clear" -> service.clearCart(owner.getId());
            default -> throw new AssertionError(operation);
        }
    }

    private Object sessionId() {
        return entityManager.createNativeQuery("select session_id()").getSingleResult();
    }

    private List<Long> productIds(Customers owner) {
        return transactions.execute(status -> carts.findByCustomerId(owner.getId()).orElseThrow()
                .getProducts().stream().map(Products::getId).toList());
    }

    private Customers newCustomer(String username) {
        Customers result = new Customers();
        result.setUsername(username);
        result.setEmail(username + "@example.com");
        result.setPassword("unused");
        result.setRole("USER");
        return customers.saveAndFlush(result);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for transaction release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}


