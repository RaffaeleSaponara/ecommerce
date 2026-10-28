package com.TreDL.ecommerce.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.ExposedPort;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.*;

/** The normal JAR uses dev + Flyway + Hibernate, against owned, disposable MySQL only. */
@Testcontainers
class DevelopmentJarIT {
    static final String RUN=UUID.randomUUID().toString();
    static final Path JAR=Path.of("target/ecommerce-0.0.1-SNAPSHOT.jar").toAbsolutePath();
    @Container static final MySQLContainer<?> MYSQL=container();
    @TempDir Path directory;
    Process child;
    Path log;
    int port;

    static MySQLContainer<?> container() {
        return new MySQLContainer<>("mysql:8.4.3")
            .withDatabaseName("3dl_checkout_dev").withUsername("3dl_dev").withPassword(UUID.randomUUID().toString())
            .withReuse(false).withLabel("com.3dl.checkout.jar-test",RUN)
            .withTmpFs(Map.of("/var/lib/mysql","rw,size=1g"))
            .withCreateContainerCmdModifier(c -> c.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(3306))));
    }

    static Connection owned(MySQLContainer<?> mysql) throws Exception {
        var info=mysql.getContainerInfo();
        assertThat(mysql.isRunning()).isTrue();
        assertThat(info.getConfig().getImage()).isEqualTo("mysql:8.4.3");
        assertThat(info.getConfig().getLabels().get("com.3dl.checkout.jar-test")).isEqualTo(RUN);
        assertThat(info.getHostConfig().getTmpFs()).containsEntry("/var/lib/mysql","rw,size=1g");
        assertThat(info.getMounts()).allMatch(m -> "tmpfs".equals(m.getRawValues().get("Type")));
        assertThat(info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(3306))[0].getHostIp()).isEqualTo("127.0.0.1");
        var identity=mysql.execInContainer("cat","/var/lib/mysql/auto.cnf");
        assertThat(identity.getExitCode()).isZero();
        String uuid=identity.getStdout().lines().filter(s -> s.startsWith("server-uuid=")).findFirst().orElseThrow().substring(12).trim();
        Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+mysql.getMappedPort(3306)+"/3dl_checkout_dev",mysql.getUsername(),mysql.getPassword());
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DATABASE(),@@server_uuid,VERSION()")) {
            assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("3dl_checkout_dev");
            assertThat(r.getString(2)).isEqualTo(uuid);assertThat(r.getString(3)).isEqualTo("8.4.3");
        } catch(Throwable failure) {c.close();throw failure;}
        return c;
    }

    static Map<String,String> settings(MySQLContainer<?> mysql) throws Exception {
        try(Connection ignored=owned(mysql)) {}
        return Map.of("DEV_DB_PORT",mysql.getMappedPort(3306).toString(),"DEV_DB_USERNAME",mysql.getUsername(),
            "DEV_DB_PASSWORD",mysql.getPassword(),"CHECKOUT_QUOTE_SECRET_BASE64",
            Base64.getEncoder().encodeToString(java.security.SecureRandom.getSeed(32)),"SERVER_PORT","0");
    }

    void start(Map<String,String> settings,String... args) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",JAR.toString()));
        command.addAll(Arrays.asList(args));
        var builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(k -> k.matches("(?i)^(SPRING_|CHECKOUT_|DEV_|DB_|SERVER_).*|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS"));
        builder.environment().putAll(settings);
        log=directory.resolve("application.log");builder.redirectOutput(log.toFile());
        child=builder.start();child.getOutputStream().close();
    }

    String output() throws Exception {return Files.exists(log)?Files.readString(log):"";}

    void ready() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(System.nanoTime()<deadline && child.isAlive()) {
            var match=java.util.regex.Pattern.compile("Tomcat started on port ([0-9]+)").matcher(output());
            if(match.find()){port=Integer.parseInt(match.group(1));break;}
            Thread.sleep(100);
        }
        assertThat(port).as("Dev JAR started").isPositive();
        assertThat(child.isAlive()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"","default","test","checkout-release"})
    void jarRefusesImplicitAndRetiredProfilesBeforeAnyDatabaseConnection(String profile) throws Exception {
        start(Map.of(),profile.isEmpty()?new String[0]:new String[]{"--spring.profiles.active="+profile});
        assertThat(child.waitFor(30,TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isNotZero();
        assertThat(output()).contains("Select the dev profile explicitly").doesNotContain("HikariPool-","Initialized JPA");
    }

    @Test void devRefusesIncompleteConfigurationBeforeAnyDatabaseConnection() throws Exception {
        start(Map.of(),"--spring.profiles.active=dev");
        assertThat(child.waitFor(30,TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isNotZero();
        assertThat(output()).doesNotContain("HikariPool-","Initialized JPA");
    }

    @Test void devJarLoadsLocalEnvironmentMigratesValidatesAndRestartsWithoutDataLoss() throws Exception {
        var env=new HashMap<>(settings(MYSQL));env.put("DEV_DEMO_DATA","true");
        // Each child runs in its own test directory, never reading the project's real .env.
        var properties=new StringBuilder();
        env.forEach((key,value) -> properties.append(key).append('=').append(value).append('\n'));
        Files.writeString(directory.resolve(".env"),properties);
        start(Map.of("SERVER_PORT","0"),"--spring.profiles.active=dev");ready();
        assertThat(output()).contains("Initialized JPA EntityManagerFactory","Successfully applied 1 migration");
        HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        URI base=URI.create("http://127.0.0.1:"+port);
        assertThat(http.send(HttpRequest.newBuilder(base.resolve("/")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        var products=http.send(HttpRequest.newBuilder(base.resolve("/prodotti/all")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(products.statusCode()).isEqualTo(200);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(products.body()).size()).isEqualTo(3);
        assertContents(MYSQL);
        for(String key:List.of("DEV_DB_PASSWORD","CHECKOUT_QUOTE_SECRET_BASE64"))assertThat(output()).doesNotContain(env.get(key));
        stop();
        start(Map.of("SERVER_PORT","0"),"--spring.profiles.active=dev");ready();assertContents(MYSQL);
        assertThat(output()).doesNotContain("Migrating schema");
        try(var zip=new ZipFile(JAR.toFile())) {
            assertThat(zip.stream().map(java.util.zip.ZipEntry::getName))
                .noneMatch(n -> n.equals(".env") || n.endsWith("/.env") || n.matches(".*(application-test.properties|Tests.class|JarIT.class|TestDatabase.class).*")
                    || n.matches("BOOT-INF/lib/(testcontainers-|junit-|spring-boot-test-|mockito-|h2-).*"));
            var migration=zip.getEntry("BOOT-INF/classes/db/checkout-v2/V1__create_checkout_v2.sql");
            assertThat(zip.getInputStream(migration).readAllBytes())
                .isEqualTo(Files.readAllBytes(Path.of("src/main/resources/db/checkout-v2/V1__create_checkout_v2.sql")));
        }
    }

    static void assertContents(MySQLContainer<?> mysql) throws Exception {
        try(Connection c=owned(mysql);Statement s=c.createStatement()) {
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM products")){r.next();assertThat(r.getInt(1)).isEqualTo(3);}
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM orders")){r.next();assertThat(r.getInt(1)).isZero();}
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        }
    }

    @Test void devRefusesAnExistingUnversionedDatabaseWithoutChangingItsData() throws Exception {
        try(MySQLContainer<?> existing=container()) {
            existing.start();
            try(Connection c=owned(existing);Statement s=c.createStatement()) {
                s.execute("CREATE TABLE unrelated_data (id INT PRIMARY KEY)");
                s.execute("INSERT INTO unrelated_data VALUES (17)");
            }
            start(settings(existing),"--spring.profiles.active=dev");
            assertThat(child.waitFor(45,TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isNotZero();
            assertThat(output()).contains("non-empty schema").doesNotContain("Initialized JPA EntityManagerFactory","Migrating schema");
            try(Connection c=owned(existing);Statement s=c.createStatement()) {
                try(ResultSet r=s.executeQuery("SELECT id FROM unrelated_data")){assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(17);}
                try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='orders'")){r.next();assertThat(r.getInt(1)).isZero();}
            }
        }
    }

    @AfterEach void stop() throws Exception {
        if(child!=null && child.isAlive()) {
            child.destroy();
            if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();}
        }
        if(child!=null)assertThat(child.isAlive()).isFalse();
        if(port>0) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            boolean closed=false;
            while(System.nanoTime()<deadline) {
                try(Socket socket=new Socket()) {socket.connect(new InetSocketAddress("127.0.0.1",port),200);}
                catch(ConnectException expected) {closed=true;break;}
                catch(SocketTimeoutException retry) { /* A timeout does not attest listener closure. */ }
                Thread.sleep(100);
            }
            assertThat(closed).as("Owned JAR listener terminated").isTrue();
            port=0;
        }
    }
}
