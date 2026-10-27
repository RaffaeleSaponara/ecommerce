package com.TreDL.ecommerce.schema;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CheckoutV2MySqlSchemaTests extends CheckoutV2SchemaContract {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.3")
            .withDatabaseName("checkout_v2_test")
            .withUsername("checkout_test")
            .withPassword("temporary-test-only");

    @Override protected String url() { return MYSQL.getJdbcUrl(); }
    @Override protected String username() { return MYSQL.getUsername(); }
    @Override protected String password() { return MYSQL.getPassword(); }
}
