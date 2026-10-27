package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.OrderDTO;
import com.TreDL.ecommerce.dto.QuoteDTO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.TreDL.ecommerce.model.Cart;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring/Hibernate + MySQL; Docker absence fails the suite, never skips it. */
@ContextConfiguration(initializers = MySqlCheckoutTestDatabase.Initializer.class)
@Execution(ExecutionMode.SAME_THREAD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutMySqlIntegrationTests extends CheckoutIntegrationContract {
    @Autowired DataSource dataSource;
    @Autowired MySqlCheckoutTestDatabase ownedDatabase;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test void ownsMySqlSchemaAndServiceStartsReadCommittedTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
        assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
        assertThat(context.getEnvironment().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(context.getEnvironment().getProperty("app.data-initializer.enabled")).isEqualTo("false");
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(ownedDatabase.databaseName());
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(ownedDatabase.expectedServerUuid());
        assertThat(jdbc.queryForObject("SELECT VERSION()",String.class)).startsWith("8.4.3");
        assertThat(jdbc.queryForObject("SELECT @@session.sql_mode",String.class)).contains("STRICT_TRANS_TABLES");
        assertThat(jdbc.queryForList("SELECT DISTINCT engine FROM information_schema.tables WHERE table_schema=DATABASE()",String.class))
                .containsExactly("InnoDB");
        AtomicReference<String> actualIsolation = new AtomicReference<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            actualIsolation.set(jdbc.queryForObject("SELECT @@session.transaction_isolation",String.class));
            return invocation.callRealMethod();
        }).when(signer).verify(anyString(),anyLong());
        checkout.checkout(alice.getEmail(),"mysql-isolation",confirm(checkout.quote(alice.getEmail())));
        assertThat(actualIsolation.get()).isEqualTo("READ-COMMITTED");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void concurrentCheckoutsUseDistinctPhysicalConnectionsAndReplayOneOrder() throws Exception {
        var quote = checkout.quote(alice.getEmail());
        CountDownLatch held = new CountDownLatch(1), waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicLong firstConnection = new AtomicLong(), secondConnection = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<OrderDTO> firstAttempt = workers.submit(() -> tx.execute(status -> {
                firstConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                OrderDTO order = checkout.checkout(alice.getEmail(),"physical-race",confirm(quote));
                held.countDown(); awaitRelease(release); return order;
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            Future<OrderDTO> secondAttempt = workers.submit(() -> tx.execute(status -> {
                secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                waiting.countDown();
                return checkout.checkout(alice.getEmail(),"physical-race",confirm(quote));
            }));
            assertThat(waiting.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(firstConnection.get()).isPositive().isNotEqualTo(secondConnection.get());
            assertThat(secondConnection.get()).isPositive();
            assertThatThrownBy(() -> secondAttempt.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(secondAttempt.get(10,TimeUnit.SECONDS)).isEqualTo(firstAttempt.get(10,TimeUnit.SECONDS));
            assertThat(orders.count()).isEqualTo(1); assertThat(cartIds()).isEmpty();
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void persistsExactBinaryColumnsDecimalMetadataAndOrderedLines() throws Exception {
        var quote = checkout.quote(alice.getEmail());
        String key = "\u00e9".repeat(64); // Exactly 128 UTF-8 bytes.
        var order = checkout.checkout(alice.getEmail(),key,confirm(quote));
        jdbc.query("SELECT idempotency_key, request_hash, total FROM orders WHERE id=?", rs -> {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBytes(1)).containsExactly(CheckoutService.keyBytes(key)).hasSize(128);
            assertThat(rs.getBytes(2)).containsExactly(QuoteSigner.requestHash(quote.quoteToken())).hasSize(32);
            assertThat(rs.getBigDecimal(3)).isEqualTo(new BigDecimal("0.40")); return null;
        },order.id());
        assertThat(jdbc.queryForList("SELECT line_number FROM order_items WHERE id_order=? ORDER BY line_number",Integer.class,order.id()))
                .containsExactly(1,2);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            assertColumn(meta,"orders","idempotency_key",Types.VARBINARY,128,0);
            assertColumn(meta,"orders","request_hash",Types.BINARY,32,0);
            assertColumn(meta,"order_items","line_number",Types.INTEGER,10,0);
            assertColumn(meta,"orders","total",Types.DECIMAL,19,2);
            assertColumn(meta,"products","prezzo",Types.DECIMAL,19,2);
            assertColumn(meta,"order_items","unit_price",Types.DECIMAL,19,2);
        }
    }

    private void assertColumn(DatabaseMetaData meta,String table,String column,int type,int precision,int scale) throws SQLException {
        try (ResultSet columns = meta.getColumns(ownedDatabase.databaseName(),null,table,column)) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getInt("DATA_TYPE")).isEqualTo(type);
            assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(precision);
            assertThat(columns.getInt("DECIMAL_DIGITS")).isEqualTo(scale);
            assertThat(columns.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNoNulls);
        }
    }

    @Test void preservesHistoryAcrossCollectionRemovalProductDeletionAndCustomerDeleteAttempt() {
        var quote = checkout.quote(alice.getEmail());
        var original = checkout.checkout(alice.getEmail(),"mysql-history",confirm(quote));
        tx.executeWithoutResult(status -> customers.findById(alice.getId()).orElseThrow().getOrders().clear());
        assertThat(orders.count()).isEqualTo(1);
        // Empty cart is unrelated to order history; remove it so only the order FK blocks the customer deletion.
        jdbc.update("DELETE FROM cart WHERE id_customer=?",alice.getId());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            customers.deleteById(alice.getId()); customers.flush();
        })).isInstanceOf(DataIntegrityViolationException.class);
        tx.executeWithoutResult(status -> products.deleteById(first.getId()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE id_order=? AND id_product IS NULL",Long.class,original.id()))
                .isEqualTo(1);
        assertThat(checkout.checkout(alice.getEmail(),"mysql-history",confirm(quote))).isEqualTo(original);
        assertThat(customers.existsById(alice.getId())).isTrue();
    }

    @Test void databaseRejectsDuplicateCustomerKeyAndForeignKeyViolations() {
        var order = checkout.checkout(alice.getEmail(),"mysql-constraints",confirm(checkout.quote(alice.getEmail())));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO orders(id_customer,date,total,currency,ship_status,idempotency_key,request_hash) "
                + "SELECT id_customer,date,total,currency,ship_status,idempotency_key,request_hash FROM orders WHERE id=?",order.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET id_customer=? WHERE id=?",Long.MAX_VALUE,order.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(orders.findById(order.id()).orElseThrow().getCustomer().getId()).isEqualTo(alice.getId());
    }

    @Test void realSqlConstraintFailureAfterOrderFlushRollsBackAllRowsAndPreservesCart() {
        var quote = checkout.quote(alice.getEmail());
        doAnswer(invocation -> {
            // The order and items were flushed in the SAME transaction: force a genuine server CHECK violation.
            jdbc.update("UPDATE orders SET total=-0.01 WHERE id_customer=?",alice.getId());
            throw new AssertionError("MySQL failed to enforce ck_orders_total");
        }).when(carts).saveAndFlush(any(Cart.class));
        assertThatThrownBy(() -> checkout.checkout(alice.getEmail(),"mysql-sql-rollback",confirm(quote)))
                .isInstanceOfSatisfying(UncategorizedSQLException.class, error -> {
                    // Connector/J exposes MySQL CHECK 3819 as HY000, not SQLState class 23.
                    assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819);
                    assertThat(error.getSQLException().getSQLState()).isEqualTo("HY000");
                    assertThat(error.getSQLException().getMessage()).contains("ck_orders_total");
                });
        intact(); reset(carts);
        checkout.checkout(alice.getEmail(),"mysql-sql-rollback",confirm(quote));
        assertThat(orders.count()).isEqualTo(1); assertThat(cartIds()).isEmpty();
    }

    @Test void guardedDatasourceRefusesCatalogEscapeBeforeExecutingMutations() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> connection.setCatalog("not_this_test")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("USE not_this_test")).isInstanceOf(SQLException.class);
        }
        intact();
    }

    @ParameterizedTest
    @CsvSource({"add,true", "remove,true", "add,false", "remove,false"})
    void quoteAndCartMutationSerializeOnCustomerWithDistinctConnections(String operation, boolean quoteFirst) throws Exception {
        CountDownLatch held = new CountDownLatch(1), waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicLong leaderConnection = new AtomicLong(), followerConnection = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> leader = workers.submit(() -> tx.execute(status -> {
                leaderConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                Object result = quoteFirst ? checkout.quote(alice.getEmail()) : mutateCart(operation);
                held.countDown(); awaitRelease(release); return result;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> follower = workers.submit(() -> tx.execute(status -> {
                followerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                waiting.countDown();
                return quoteFirst ? mutateCart(operation) : checkout.quote(alice.getEmail());
            }));
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(leaderConnection.get()).isPositive().isNotEqualTo(followerConnection.get());
            assertThat(followerConnection.get()).isPositive();
            assertThatThrownBy(() -> follower.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            Object firstResult = leader.get(10, TimeUnit.SECONDS), secondResult = follower.get(10, TimeUnit.SECONDS);
            QuoteDTO quote = (QuoteDTO) (quoteFirst ? firstResult : secondResult);
            int finalQuantity = operation.equals("add") ? 3 : 1;
            int quotedQuantity = quoteFirst ? 2 : finalQuantity;
            assertThat(quote.items()).extracting(line -> line.quantity()).containsExactly(quotedQuantity, 1);
            assertThat(quote.total()).isEqualTo(new BigDecimal("0.10").multiply(BigDecimal.valueOf(quotedQuantity)).add(new BigDecimal("0.20")));
            assertThat(signer.verify(quote.quoteToken(), alice.getId()).items()).isEqualTo(quote.items());
            assertThat(cartIds()).hasSize(finalQuantity + 1);
            assertThat(orders.count()).isZero();
            // A quote serialized BEFORE a later mutation is valid for its snapshot,
            // but must never authorize checkout against the changed cart.
            if (quoteFirst) rejected(() -> checkout.checkout(alice.getEmail(), "quote-before-mutation", confirm(quote)), 409);
            assertThat(cartIds()).hasSize(finalQuantity + 1);
            assertThat(orders.count()).isZero();
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Object mutateCart(String operation) {
        return operation.equals("add") ? cartService.addProductToCart(alice, first)
                : cartService.removeProductToCart(alice, first);
    }

    private static void awaitRelease(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
