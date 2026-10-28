package com.TreDL.ecommerce.service;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Test-only owner of one non-reusable, memory-backed MySQL instance. */
final class MySqlCheckoutTestDatabase implements AutoCloseable {
    static final String IMAGE = "mysql:8.4.3";
    private final String run = UUID.randomUUID().toString().replace("-", "");
    private final String database = "checkout_v2_it_" + run;
    private final MySQLContainer<?> mysql = new MySQLContainer<>(IMAGE)
            .withDatabaseName(database)
            .withUsername("checkout_it")
            .withPassword(UUID.randomUUID().toString()) // Ephemeral fixture, never a real credential.
            .withLabel("com.3dl.checkout.run", run)
            .withReuse(false)
            .withTmpFs(Map.of("/var/lib/mysql", "rw,size=1g"))
            .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(3306))));
    private GuardedDataSource source;
    private String serverUuid;

    static final class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            MySqlCheckoutTestDatabase database = new MySqlCheckoutTestDatabase();
            try {
                database.startAndMigrate(); // Before any Spring/JPA entity is used.
                GenericApplicationContext generic = (GenericApplicationContext) context;
                generic.registerBeanDefinition("checkoutMySqlDatabase",
                        BeanDefinitionBuilder.genericBeanDefinition(MySqlCheckoutTestDatabase.class, () -> database)
                                .setDestroyMethodName("close").getBeanDefinition());
                generic.registerBeanDefinition("dataSource",
                        BeanDefinitionBuilder.genericBeanDefinition(GuardedDataSource.class, () -> database.source)
                                .addDependsOn("checkoutMySqlDatabase").getBeanDefinition());
                // No reliance on application datasource configuration, including driver/credentials.
                Map<String, Object> properties = new HashMap<>();
                properties.put("spring.datasource.url", database.jdbcUrl());
                properties.put("spring.datasource.username", database.mysql.getUsername());
                properties.put("spring.datasource.password", database.mysql.getPassword());
                properties.put("spring.datasource.driver-class-name", database.mysql.getDriverClassName());
                properties.put("spring.jpa.hibernate.ddl-auto", "validate");
                properties.put("spring.jpa.open-in-view", "false");
                properties.put("spring.flyway.enabled", "false");
                properties.put("app.data-initializer.enabled", "false");
                context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("owned-checkout-mysql", properties));
            } catch (Exception e) {
                database.close();
                throw new IllegalStateException("Cannot initialize isolated Checkout MySQL", e);
            }
        }
    }

    private void startAndMigrate() throws Exception {
        mysql.start();
        var info = mysql.getContainerInfo();
        if (!mysql.isRunning() || !IMAGE.equals(info.getConfig().getImage())
                || !run.equals(info.getConfig().getLabels().get("com.3dl.checkout.run"))
                || !"rw,size=1g".equals(info.getHostConfig().getTmpFs().get("/var/lib/mysql")))
            throw new IllegalStateException("Unexpected container ownership or storage");
        if(info.getMounts().stream().anyMatch(m->!"tmpfs".equals(m.getRawValues().get("Type")))
                || !"127.0.0.1".equals(info.getNetworkSettings().getPorts().getBindings()
                    .get(com.github.dockerjava.api.model.ExposedPort.tcp(3306))[0].getHostIp()))
            throw new IllegalStateException("Test MySQL must use tmpfs and loopback binding only");
        var identity = mysql.execInContainer("cat", "/var/lib/mysql/auto.cnf");
        Matcher uuid = Pattern.compile("(?m)^server-uuid\\s*=\\s*([a-fA-F0-9-]+)\\s*$").matcher(identity.getStdout());
        if (identity.getExitCode() != 0 || !uuid.find()) throw new IllegalStateException("Cannot establish server UUID");
        serverUuid = uuid.group(1);
        source = new GuardedDataSource();
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")) {
            tables.next();
            if (tables.getLong(1) != 0) throw new IllegalStateException("Test schema is not empty");
        }
        Flyway.configure().dataSource(source).locations("classpath:db/checkout-v2")
                .cleanDisabled(true).baselineOnMigrate(false).createSchemas(false).load().migrate();
    }

    // Use the verified IPv4 loopback binding, independent of Testcontainers host discovery.
    private String jdbcUrl() {
        return "jdbc:mysql://127.0.0.1:" + mysql.getMappedPort(3306) + "/" + database;
    }

    String databaseName() { return database; }
    String expectedServerUuid() { return serverUuid; }

    /** Identity query runs on the underlying connection, avoiding proxy recursion. */
    private void assertIdentity(Connection raw) throws SQLException {
        String expectedUrl = jdbcUrl().split("\\?", 2)[0];
        if (!expectedUrl.equals(raw.getMetaData().getURL().split("\\?", 2)[0]))
            throw new SQLException("Refusing unexpected JDBC destination");
        try (Statement check = raw.createStatement(); ResultSet identity = check.executeQuery(
                "SELECT DATABASE(), @@server_uuid, VERSION()")) {
            if (!identity.next() || !database.equals(identity.getString(1))
                    || !serverUuid.equals(identity.getString(2)) || !identity.getString(3).startsWith("8.4.3"))
                throw new SQLException("Refusing unexpected MySQL database/server identity");
        }
    }

    final class GuardedDataSource extends AbstractDataSource {
        private final DriverManagerDataSource delegate;
        GuardedDataSource() {
            delegate = new DriverManagerDataSource(jdbcUrl(), mysql.getUsername(), mysql.getPassword());
            delegate.setDriverClassName(mysql.getDriverClassName());
        }
        @Override public Connection getConnection() throws SQLException {
            if (!mysql.isRunning()) throw new SQLException("Owned test container is not running");
            Connection raw = delegate.getConnection();
            try {
                assertIdentity(raw);
                return wrapConnection(raw);
            } catch (Exception e) { raw.close(); throw e; }
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("External datasource credentials are not accepted");
        }
    }

    private Connection wrapConnection(Connection raw) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("unwrap")) return unwrap(proxy, (Class<?>) args[0]);
                    if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
                    if (name.equals("setCatalog") && !database.equals(args[0]))
                        throw new SQLException("Changing the owned catalog is forbidden");
                    if (name.equals("commit")) assertIdentity(raw);
                    if (name.equals("createStatement") || name.equals("prepareStatement") || name.equals("prepareCall")) {
                        assertIdentity(raw);
                        String sql = args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
                        return wrapStatement((Statement) invoke(method, raw, args), raw, (Connection) proxy, sql);
                    }
                    return invoke(method, raw, args);
                });
    }

    private Statement wrapStatement(Statement statement, Connection raw, Connection guarded, String preparedSql) {
        Class<?> api = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{api},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getConnection")) return guarded;
                    if (name.equals("unwrap")) return unwrap(proxy, (Class<?>) args[0]);
                    if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
                    if (name.startsWith("execute") || name.equals("addBatch")) {
                        assertIdentity(raw); // Before migrations, fixture DELETEs and every JDBC execution.
                        String sql = args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : preparedSql;
                        if (sql != null && Pattern.compile("(?is)^\\s*(?:USE\\b|(?:DROP|CREATE)\\s+(?:DATABASE|SCHEMA)\\b)").matcher(sql).find())
                            throw new SQLException("Database switching/creation/deletion is forbidden");
                    }
                    return invoke(method, statement, args);
                });
    }

    private static Object unwrap(Object proxy, Class<?> type) throws SQLException {
        if (type.isInstance(proxy)) return proxy;
        throw new SQLFeatureNotSupportedException("Native JDBC unwrapping is disabled for test isolation");
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    @Override public void close() { mysql.stop(); } // Only this instance, including its tmpfs.
}
