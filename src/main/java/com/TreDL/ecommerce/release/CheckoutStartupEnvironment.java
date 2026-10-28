package com.TreDL.ecommerce.release;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import java.util.*;

/** Fail closed before bean creation, and recheck final configuration before JPA. */
public final class CheckoutStartupEnvironment implements EnvironmentPostProcessor, Ordered {
    static final String DEVELOPMENT = "checkout-browser";
    static final String BOOTSTRAP = "com.TreDL.ecommerce.tools.CheckoutBrowserLauncher$DevelopmentBootstrap";
    static final String DEVELOPMENT_SOURCE = "com.TreDL.ecommerce.tools.CheckoutBrowserLauncher$GuardedSource";
    static final String TEST_SOURCE = "com.TreDL.ecommerce.service.MySqlCheckoutTestDatabase$GuardedDataSource";
    private static final List<String> ALTERNATE_CONNECTIONS = List.of(
        "spring.datasource.jndi-name", "spring.datasource.type",
        "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.jdbcUrl",
        "spring.datasource.hikari.data-source-class-name", "spring.datasource.hikari.dataSourceClassName",
        "spring.datasource.hikari.data-source-jndi", "spring.datasource.hikari.data-source-properties.url",
        "spring.datasource.hikari.data-source-properties.jdbcUrl",
        "spring.jpa.properties.hibernate.connection.url", "spring.jpa.properties.hibernate.connection.datasource",
        "spring.jpa.properties.hibernate.connection.provider_class",
        "spring.jpa.properties.hibernate.connection.driver_class",
        "spring.jpa.properties.jakarta.persistence.jdbc.url", "spring.jpa.properties.javax.persistence.jdbc.url",
        "spring.jpa.properties.jakarta.persistence.jtaDataSource", "spring.jpa.properties.javax.persistence.jtaDataSource",
        "spring.jpa.properties.jakarta.persistence.nonJtaDataSource", "spring.jpa.properties.javax.persistence.nonJtaDataSource"
    );

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 20; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        rejectAlternateConnections(env);
        boolean development = application != null && application.getAllSources().stream()
            .anyMatch(source -> source instanceof Class<?> type && BOOTSTRAP.equals(type.getName()));
        if (Arrays.asList(env.getActiveProfiles()).contains(ReleaseDatabase.PROFILE)
            || (env.getActiveProfiles().length == 0 && Arrays.asList(env.getDefaultProfiles()).contains(ReleaseDatabase.PROFILE))) {
            new ReleaseEnvironment().postProcessEnvironment(env, application);
        } else {
            validateIsolated(env, development, null);
        }
        if (application != null) {
            String[] approvedProfiles = env.getActiveProfiles().clone();
            application.addInitializers(context -> context.addBeanFactoryPostProcessor(factory -> {
                ReleaseDatabase.require(Arrays.equals(approvedProfiles, context.getEnvironment().getActiveProfiles()),
                    "Startup profiles changed after preflight");
                validateFinal(context.getEnvironment(), development, factory);
            }));
        }
    }

    static void validateFinal(ConfigurableEnvironment env, boolean development, ConfigurableListableBeanFactory factory) {
        rejectAlternateConnections(env);
        if (Arrays.equals(env.getActiveProfiles(), new String[]{ReleaseDatabase.PROFILE})) {
            new ReleaseEnvironment().postProcessEnvironment(env, null);
        } else {
            validateIsolated(env, development, factory);
        }
    }

    private static void rejectAlternateConnections(ConfigurableEnvironment env) {
        for (String key : ALTERNATE_CONNECTIONS)
            ReleaseDatabase.require(env.getProperty(key) == null, "Alternate database configuration forbidden: " + key);
        for (String namespace : List.of("jakarta", "javax")) {
            String key = "spring.jpa.properties." + namespace + ".persistence.schema-generation.database.action";
            ReleaseDatabase.require(env.getProperty(key) == null, "JPA schema generation override forbidden");
        }
    }

    private static void validateIsolated(ConfigurableEnvironment env, boolean development,
                                         ConfigurableListableBeanFactory factory) {
        String[] profiles = env.getActiveProfiles();
        if (Arrays.equals(profiles, new String[]{DEVELOPMENT})) {
            ReleaseDatabase.require(development, "Isolated development requires tools/checkout-browser.ps1");
            for (String key : List.of("spring.datasource.url", "spring.datasource.username",
                    "spring.datasource.password", "spring.datasource.driver-class-name"))
                ReleaseDatabase.require(env.getProperty(key) == null, "External development datasource forbidden");
            requireValue(env, "spring.jpa.hibernate.ddl-auto", "validate");
            requireAlias(env, "validate");
            requireValue(env, "spring.jpa.generate-ddl", "false");
            requireValue(env, "spring.sql.init.mode", "never");
            requireValue(env, "spring.jpa.open-in-view", "false");
            requireValue(env, "spring.flyway.enabled", "false");
            requireValue(env, "server.address", "127.0.0.1");
            if (factory != null) requireSource(factory, DEVELOPMENT_SOURCE);
            return;
        }
        ReleaseDatabase.require(Arrays.equals(profiles, new String[]{"test"}) && hasTestRuntime(),
            "Explicit Checkout V2 startup required: checkout-release or isolated development runner");
        requireValue(env, "app.data-initializer.enabled", "false");
        requireValue(env, "spring.flyway.enabled", "false");
        String url = env.getProperty("spring.datasource.url", "");
        boolean memory = url.matches("jdbc:h2:mem:[A-Za-z0-9_-]+(?:;(?:MODE=MySQL|DATABASE_TO_LOWER=TRUE|DB_CLOSE_DELAY=-1|DB_CLOSE_ON_EXIT=FALSE|LOCK_TIMEOUT=[1-9][0-9]*))*");
        boolean ownedMysql = url.matches("jdbc:mysql://127\\.0\\.0\\.1:(?!3306/)[1-9][0-9]{1,4}/checkout_v2_it_[a-f0-9]{32}");
        ReleaseDatabase.require(memory || ownedMysql, "Test datasource must be isolated memory or owned MySQL");
        String ddl = env.getProperty("spring.jpa.hibernate.ddl-auto");
        ReleaseDatabase.require("validate".equals(ddl) || (memory && "create-drop".equals(ddl)),
            "Unsafe Hibernate schema policy");
        requireAlias(env, ddl);
        if (ownedMysql && factory != null) requireSource(factory, TEST_SOURCE);
    }

    private static boolean hasTestRuntime() {
        ClassLoader loader = CheckoutStartupEnvironment.class.getClassLoader();
        return loader.getResource("application-test.properties") != null
            && loader.getResource("com/TreDL/ecommerce/EcommerceApplicationTests.class") != null
            && loader.getResource("org/junit/jupiter/api/Test.class") != null;
    }

    private static void requireSource(ConfigurableListableBeanFactory factory, String expected) {
        ReleaseDatabase.require(factory.containsBeanDefinition("dataSource")
            && expected.equals(factory.getBeanDefinition("dataSource").getBeanClassName()),
            "Owned isolated datasource required before JPA");
    }

    private static void requireAlias(ConfigurableEnvironment env, String expected) {
        String alias = env.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto");
        ReleaseDatabase.require(alias == null || Objects.equals(alias, expected), "Hibernate schema alias mismatch");
    }

    private static void requireValue(ConfigurableEnvironment env, String key, String expected) {
        ReleaseDatabase.require(expected.equals(env.getProperty(key)), "Unsafe startup setting: " + key);
    }
}
