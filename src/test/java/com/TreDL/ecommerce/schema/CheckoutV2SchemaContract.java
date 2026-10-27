package com.TreDL.ecommerce.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** JDBC-only contract: never loads Spring, Hibernate or application datasources. */
abstract class CheckoutV2SchemaContract {
    private Connection db;
    private Flyway flyway;
    private String jdbcUrl;

    protected abstract String url();
    protected abstract String username();
    protected abstract String password();

    @BeforeEach
    void migrateTemporaryDatabase() throws Exception {
        jdbcUrl = url();
        // Implementations supply either a new H2 UUID or the managed container URL.
        db = DriverManager.getConnection(jdbcUrl, username(), password());
        flyway = Flyway.configure().dataSource(jdbcUrl, username(), password())
                .locations("classpath:db/checkout-v2")
                .cleanDisabled(true).baselineOnMigrate(false).load();
        flyway.migrate();
        db.setAutoCommit(false);
        sql("INSERT INTO customers(id, username, password, role) VALUES (1, 'alice', 'hash', 'USER'), (2, 'bob', 'hash', 'USER')");
        sql("INSERT INTO products(id, nome, prezzo) VALUES (1, 'Original', 0.10)");
    }

    @AfterEach
    void releaseTemporaryDatabase() throws Exception {
        if (db != null) {
            try {
                db.rollback();
                // Only fixture rows inside this test's H2/container database.
                for (String table : new String[] {"order_items", "orders", "cart_products", "cart", "products", "customers"}) {
                    sql("DELETE FROM " + table);
                }
                db.commit();
            } finally { db.close(); }
        }
    }

    @Test
    void migrationIsVersionedValidatedAndRepeatableWithoutChanges() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void moneyHasExactPrecisionAndRemainsANumericJsonValue() throws Exception {
        order(1, 1, "money", new BigDecimal("0.30"));
        item(1, 1, "Original", "0.10", 3);
        for (String[] column : new String[][] {
                {"products", "prezzo"}, {"orders", "total"}, {"order_items", "unit_price"}}) {
            try (var columns = db.getMetaData().getColumns(null, null, column[0], column[1])) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(19);
                assertThat(columns.getInt("DECIMAL_DIGITS")).isEqualTo(2);
            }
        }
        try (var statement = db.createStatement(); var result = statement.executeQuery(
                "SELECT o.total, i.unit_price * i.quantity AS line_total FROM orders o JOIN order_items i ON i.id_order = o.id")) {
            assertThat(result.next()).isTrue();
            BigDecimal total = result.getBigDecimal("total");
            assertThat(total).isEqualByComparingTo("0.30");
            assertThat(result.getBigDecimal("line_total")).isEqualByComparingTo(total);
            var mapper = new ObjectMapper();
            assertThat(mapper.readTree(mapper.writeValueAsString(Map.of("total", total)))
                    .get("total").isNumber()).isTrue();
        }
        sql("UPDATE products SET prezzo = 99999999999999999.99 WHERE id = 1");
        assertThat(scalarDecimal("SELECT prezzo FROM products WHERE id = 1"))
                .isEqualByComparingTo("99999999999999999.99");
        assertThatThrownBy(() -> sql("UPDATE products SET prezzo = 100000000000000000.00 WHERE id = 1"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void historicalItemsSurviveCatalogChangesAndDeletion() throws Exception {
        order(1, 1, "history", new BigDecimal("0.30"));
        item(1, 1, "Original", "0.10", 3);
        sql("UPDATE products SET nome = 'Changed', prezzo = 99.99 WHERE id = 1");
        sql("DELETE FROM products WHERE id = 1");
        try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT * FROM order_items")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject("id_product")).isNull();
            assertThat(result.getString("product_name")).isEqualTo("Original");
            assertThat(result.getBigDecimal("unit_price")).isEqualByComparingTo("0.10");
            assertThat(result.getInt("quantity")).isEqualTo(3);
            assertThat(result.getInt("line_number")).isEqualTo(1);
        }
    }

    @Test
    void committedLineNumbersDefineHistoricalOrderRegardlessOfInsertionOrder() throws Exception {
        order(1, 1, "line-order", new BigDecimal("0.60"));
        item(1, 3, 1, "Third", "0.30", 1);
        item(1, 1, 1, "First", "0.10", 1);
        item(1, 2, 1, "Second", "0.20", 1);
        db.commit();
        try (var reader = DriverManager.getConnection(jdbcUrl, username(), password());
             var statement = reader.prepareStatement(
                     "SELECT line_number, product_name FROM order_items WHERE id_order = ? ORDER BY line_number")) {
            statement.setLong(1, 1);
            try (var result = statement.executeQuery()) {
                String[] names = {"First", "Second", "Third"};
                for (int index = 0; index < names.length; index++) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt("line_number")).isEqualTo(index + 1);
                    assertThat(result.getString("product_name")).isEqualTo(names[index]);
                }
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void duplicateLineNumbersInTheSameOrderAreRejectedOnInsertAndUpdate() throws Exception {
        order(1, 1, "duplicate-line", new BigDecimal("0.20"));
        item(1, 1, 1, "First", "0.10", 1);
        assertThatThrownBy(() -> item(1, 1, 1, "Duplicate", "0.10", 1))
                .isInstanceOf(SQLException.class);
        item(1, 2, 1, "Second", "0.10", 1);
        assertThatThrownBy(() -> sql("UPDATE order_items SET line_number = 1 WHERE id_order = 1 AND line_number = 2"))
                .isInstanceOf(SQLException.class);
        assertThat(scalarDecimal("SELECT COUNT(*) FROM order_items WHERE id_order = 1"))
                .isEqualByComparingTo("2");
        assertThat(scalarDecimal("SELECT COUNT(*) FROM order_items WHERE id_order = 1 AND line_number = 2"))
                .isEqualByComparingTo("1");
    }

    @Test
    void lineNumbersCanBeReusedAcrossOrdersOfTheSameCustomer() throws Exception {
        order(1, 1, "first-order", new BigDecimal("0.10"));
        order(2, 1, "second-order", new BigDecimal("0.10"));
        item(1, 1, 1, "First order", "0.10", 1);
        item(2, 1, 1, "Second order", "0.10", 1);
        assertThat(scalarDecimal("SELECT COUNT(*) FROM order_items WHERE line_number = 1"))
                .isEqualByComparingTo("2");
    }

    @Test
    void lineNumbersMustBePresentAndPositiveOnInsertAndUpdate() throws Exception {
        order(1, 1, "invalid-line", new BigDecimal("0.10"));
        for (Integer invalid : new Integer[] {null, 0, -1}) {
            assertThatThrownBy(() -> item(1, invalid, 1, "Invalid", "0.10", 1))
                    .isInstanceOf(SQLException.class);
        }
        assertThat(scalarDecimal("SELECT COUNT(*) FROM order_items")).isEqualByComparingTo("0");
        item(1, 1, 1, "Valid", "0.10", 1);
        for (String invalid : new String[] {"NULL", "0", "-1"}) {
            assertThatThrownBy(() -> sql("UPDATE order_items SET line_number = " + invalid + " WHERE id_order = 1"))
                    .isInstanceOf(SQLException.class);
        }
        assertThat(scalarDecimal("SELECT line_number FROM order_items WHERE id_order = 1"))
                .isEqualByComparingTo("1");
    }

    @Test
    void idempotencyIsUniquePerCustomerAndCaseSensitive() throws Exception {
        order(1, 1, "Key", BigDecimal.ZERO);
        assertThatThrownBy(() -> order(2, 1, "Key", BigDecimal.ZERO)).isInstanceOf(SQLException.class);
        order(3, 2, "Key", BigDecimal.ZERO);
        order(4, 1, "key", BigDecimal.ZERO);
        assertThat(scalarDecimal("SELECT COUNT(*) FROM orders")).isEqualByComparingTo("3");
        try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT request_hash FROM orders WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBytes(1)).hasSize(32);
        }
        assertThatThrownBy(() -> order(5, 1, "", BigDecimal.ZERO)).isInstanceOf(SQLException.class);
    }

    @Test
    void committedIdempotencyAndSnapshotsCanBeReadFromAnotherConnection() throws Exception {
        order(1, 1, "durable", new BigDecimal("0.30"));
        item(1, 1, "Original", "0.10", 3);
        db.commit();
        try (var reader = DriverManager.getConnection(jdbcUrl, username(), password());
             var statement = reader.createStatement();
             var result = statement.executeQuery("SELECT o.idempotency_key, o.total, i.product_name FROM orders o JOIN order_items i ON i.id_order = o.id WHERE o.id_customer = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBytes(1)).isEqualTo("durable".getBytes(StandardCharsets.UTF_8));
            assertThat(result.getBigDecimal(2)).isEqualByComparingTo("0.30");
            assertThat(result.getString(3)).isEqualTo("Original");
        }
        assertThatThrownBy(() -> order(2, 1, "durable", BigDecimal.ZERO)).isInstanceOf(SQLException.class);
    }

    @Test
    void cartKeepsRepeatedOccurrencesAndOneCartPerCustomer() throws Exception {
        sql("INSERT INTO cart(id, id_customer) VALUES (1, 1)");
        sql("INSERT INTO cart_products(id_cart, id_product) VALUES (1, 1), (1, 1), (1, 1)");
        assertThat(scalarDecimal("SELECT COUNT(*) FROM cart_products")).isEqualByComparingTo("3");
        assertThatThrownBy(() -> sql("INSERT INTO cart(id, id_customer) VALUES (2, 1)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void invalidMoneyQuantityCurrencyAndReferencesAreRejected() throws Exception {
        order(1, 1, "constraints", BigDecimal.ZERO);
        assertThatThrownBy(() -> item(1, 1, "Original", "0.10", 0)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> item(1, 1, "Original", "0.10", -1)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> item(1, 1, "Original", "-0.01", 1)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> item(999, 1, "Original", "0.10", 1)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> item(1, 999, "Original", "0.10", 1)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("UPDATE products SET prezzo = -0.01 WHERE id = 1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("UPDATE orders SET total = -0.01 WHERE id = 1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("UPDATE orders SET currency = 'USD' WHERE id = 1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("UPDATE orders SET currency = 'eur' WHERE id = 1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("UPDATE orders SET request_hash = NULL WHERE id = 1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("UPDATE orders SET idempotency_key = NULL WHERE id = 1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> order(2, 999, "missing", BigDecimal.ZERO)).isInstanceOf(SQLException.class);
        try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT currency FROM orders WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("EUR");
        }
    }

    @Test
    void rollbackRestoresCartAndReleasesIdempotencyKey() throws Exception {
        sql("INSERT INTO cart(id, id_customer) VALUES (1, 1)");
        sql("INSERT INTO cart_products(id_cart, id_product) VALUES (1, 1), (1, 1)");
        var savepoint = db.setSavepoint();
        order(1, 1, "retry", new BigDecimal("0.20"));
        item(1, 1, "Original", "0.10", 2);
        sql("DELETE FROM cart_products WHERE id_cart = 1");
        db.rollback(savepoint);
        assertThat(scalarDecimal("SELECT COUNT(*) FROM orders")).isEqualByComparingTo("0");
        assertThat(scalarDecimal("SELECT COUNT(*) FROM order_items")).isEqualByComparingTo("0");
        assertThat(scalarDecimal("SELECT COUNT(*) FROM cart_products")).isEqualByComparingTo("2");
        order(2, 1, "retry", new BigDecimal("0.20"));
    }

    @Test
    void noLegacyOrderDetailsTableIsCreated() throws Exception {
        try (var tables = db.getMetaData().getTables(null, null, "order_details", new String[] {"TABLE"})) {
            assertThat(tables.next()).isFalse();
        }
    }

    private void sql(String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.executeUpdate(sql); }
    }

    private BigDecimal scalarDecimal(String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getBigDecimal(1);
        }
    }

    private void order(long id, long customer, String key, BigDecimal total) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO orders(id, id_customer, date, total, ship_status, idempotency_key, request_hash) VALUES (?, ?, CURRENT_TIMESTAMP, ?, 'In elaborazione', ?, ?)")) {
            statement.setLong(1, id);
            statement.setLong(2, customer);
            statement.setBigDecimal(3, total);
            statement.setBytes(4, key.getBytes(StandardCharsets.UTF_8));
            statement.setBytes(5, new byte[32]);
            statement.executeUpdate();
        }
    }

    private void item(long order, long product, String name, String price, int quantity) throws SQLException {
        item(order, 1, product, name, price, quantity);
    }

    private void item(long order, Integer lineNumber, long product, String name, String price, int quantity) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO order_items(id_order, id_product, product_name, unit_price, quantity, line_number) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, order);
            statement.setLong(2, product);
            statement.setString(3, name);
            statement.setBigDecimal(4, new BigDecimal(price));
            statement.setInt(5, quantity);
            statement.setObject(6, lineNumber, java.sql.Types.INTEGER);
            statement.executeUpdate();
        }
    }
}
