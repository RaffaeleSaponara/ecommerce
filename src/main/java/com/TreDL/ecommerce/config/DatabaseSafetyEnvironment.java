package com.TreDL.ecommerce.config;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import java.util.*;

/** Small startup guard: a dedicated local dev database, or owned test databases. */
public final class DatabaseSafetyEnvironment implements EnvironmentPostProcessor, Ordered {
    private static final List<String> ALTERNATE_CONNECTIONS = List.of(
        "spring.datasource.jndi-name", "spring.datasource.type",
        "spring.datasource.hikari.catalog", "spring.datasource.hikari.schema",
        "spring.datasource.hikari.connection-init-sql", "spring.datasource.hikari.connectionInitSql",
        "spring.jpa.properties.hibernate.default_catalog", "spring.jpa.properties.hibernate.default_schema",
        "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.jdbcUrl",
        "spring.datasource.hikari.data-source-class-name", "spring.datasource.hikari.dataSourceClassName",
        "spring.datasource.hikari.data-source-jndi", "spring.datasource.hikari.data-source-properties.url",
        "spring.datasource.hikari.data-source-properties.jdbcUrl",
        "spring.jpa.properties.hibernate.connection.url", "spring.jpa.properties.hibernate.connection.datasource",
        "spring.jpa.properties.hibernate.connection.provider_class", "spring.jpa.properties.hibernate.connection.driver_class",
        "spring.jpa.properties.jakarta.persistence.jdbc.url", "spring.jpa.properties.javax.persistence.jdbc.url",
        "spring.jpa.properties.jakarta.persistence.jtaDataSource", "spring.jpa.properties.javax.persistence.jtaDataSource",
        "spring.jpa.properties.jakarta.persistence.nonJtaDataSource", "spring.jpa.properties.javax.persistence.nonJtaDataSource",
        "spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
        "spring.flyway.schemas", "spring.flyway.default-schema", "spring.flyway.init-sqls"
    );

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 20; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        validate(env, null);
        if (application != null)
            application.addInitializers(context -> context.addBeanFactoryPostProcessor(
                factory -> validate(context.getEnvironment(), factory)));
    }

    static void validate(ConfigurableEnvironment env, ConfigurableListableBeanFactory factory) {
        for (String key : ALTERNATE_CONNECTIONS)
            require(env.getProperty(key) == null, "Alternate database configuration forbidden: " + key);
        for (String namespace : List.of("jakarta", "javax"))
            require(env.getProperty("spring.jpa.properties." + namespace + ".persistence.schema-generation.database.action") == null,
                "JPA schema generation override forbidden");
        boolean dev = Arrays.equals(env.getActiveProfiles(), new String[]{"dev"});
        boolean test = Arrays.equals(env.getActiveProfiles(), new String[]{"test"}) && hasTestRuntime();
        require(dev || test, "Select the dev profile explicitly; no default database is configured");
        String url = env.getProperty("spring.datasource.url", "");
        String ddl = env.getProperty("spring.jpa.hibernate.ddl-auto", "");
        String alias = env.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto");
        require(alias == null || alias.equals(ddl), "Hibernate schema alias mismatch");
        if (dev) {
            var match = java.util.regex.Pattern.compile(
                "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):([1-9][0-9]{0,4})/3dl_checkout_dev(?:\\?allowPublicKeyRetrieval=true&sslMode=PREFERRED)?").matcher(url);
            require(match.matches() && Integer.parseInt(match.group(1)) <= 65535,
                "Dev requires the local 3dl_checkout_dev database");
            String username = env.getProperty("spring.datasource.username", "");
            require(!username.isBlank() && !username.equalsIgnoreCase("root"), "Use a dedicated non-root dev account");
            require(!env.getProperty("spring.datasource.password", "").isBlank(), "Dev database password is required");
            try {
                require(Base64.getDecoder().decode(env.getProperty("checkout.quote-secret-base64", "")).length >= 32,
                    "A Base64 checkout HMAC key of at least 32 bytes is required");
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid checkout HMAC key");
            }
            require("validate".equals(ddl), "Hibernate must validate, never change the dev schema");
            for (var entry : Map.of(
                    "spring.flyway.enabled", "true",
                    "spring.flyway.clean-disabled", "true",
                    "spring.flyway.baseline-on-migrate", "false",
                    "spring.flyway.create-schemas", "false",
                    "spring.flyway.validate-on-migrate", "true",
                    "spring.flyway.locations", "classpath:db/checkout-v2",
                    "spring.jpa.generate-ddl", "false",
                    "spring.sql.init.mode", "never",
                    "spring.jpa.open-in-view", "false",
                    "server.address", "127.0.0.1").entrySet())
                require(entry.getValue().equals(env.getProperty(entry.getKey())), "Unsafe dev setting: " + entry.getKey());
            return;
        }
        require("false".equals(env.getProperty("spring.flyway.enabled")), "Tests cannot run automatic migrations");
        require("false".equals(env.getProperty("app.data-initializer.enabled")), "Tests cannot seed demo data");
        boolean memory = url.matches("jdbc:h2:mem:[A-Za-z0-9_-]+(?:;(?:MODE=MySQL|DATABASE_TO_LOWER=TRUE|DB_CLOSE_DELAY=-1|DB_CLOSE_ON_EXIT=FALSE|LOCK_TIMEOUT=[1-9][0-9]*))*");
        boolean mysql = url.matches("jdbc:mysql://127\\.0\\.0\\.1:(?!3306/)[1-9][0-9]{1,4}/checkout_v2_it_[a-f0-9]{32}");
        require(memory || mysql, "Test datasource must be isolated memory or owned MySQL");
        require("validate".equals(ddl) || (memory && "create-drop".equals(ddl)), "Unsafe test schema policy");
        if (mysql && factory != null)
            require(factory.containsBeanDefinition("dataSource") &&
                "com.TreDL.ecommerce.service.MySqlCheckoutTestDatabase$GuardedDataSource"
                    .equals(factory.getBeanDefinition("dataSource").getBeanClassName()),
                "Owned isolated datasource required before JPA");
    }

    private static boolean hasTestRuntime() {
        ClassLoader loader = DatabaseSafetyEnvironment.class.getClassLoader();
        return loader.getResource("application-test.properties") != null
            && loader.getResource("com/TreDL/ecommerce/EcommerceApplicationTests.class") != null
            && loader.getResource("org/junit/jupiter/api/Test.class") != null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
