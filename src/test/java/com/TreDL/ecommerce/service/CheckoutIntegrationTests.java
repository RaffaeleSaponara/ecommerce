package com.TreDL.ecommerce.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

/** Original H2 suite; the same behavioral contract is also run on MySQL. */
class CheckoutIntegrationTests extends CheckoutIntegrationContract {
    static final String URL = "jdbc:h2:mem:checkout_integration_" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000";
    static Connection keeper;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        keeper = DriverManager.getConnection(URL, "sa", "");
        Flyway.configure().dataSource(URL,"sa","").locations("classpath:db/checkout-v2")
                .cleanDisabled(true).baselineOnMigrate(false).load().migrate();
        properties.add("spring.datasource.url", () -> URL);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.jpa.open-in-view", () -> "false");
    }
    @AfterAll static void closeDatabase() throws Exception { if (keeper != null) keeper.close(); }

}
