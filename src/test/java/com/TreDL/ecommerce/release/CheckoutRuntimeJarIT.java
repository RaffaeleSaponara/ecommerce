package com.TreDL.ecommerce.release;

import org.flywaydb.core.Flyway;
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
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.*;

/** Runs after package: exercises the real application JAR, never test classes in its child JVM. */
@Testcontainers
class CheckoutRuntimeJarIT {
    static final String RUN=UUID.randomUUID().toString().replace("-","");
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("checkoutv2"+RUN).withUsername("provisioner").withPassword(UUID.randomUUID().toString())
        .withReuse(false).withLabel("com.3dl.checkout.jar-test",RUN)
        .withTmpFs(Map.of("/var/lib/mysql","rw,size=1g"))
        .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(3306))));
    static final Path JAR=Path.of("target/ecommerce-0.0.1-SNAPSHOT.jar").toAbsolutePath();
    static String uuid;
    static Map<String,String> runtime;
    @TempDir Path directory;
    Process child;
    Path log;
    int port;

    static Connection ownedAdmin() throws Exception {
        var info=MYSQL.getContainerInfo();
        assertThat(info.getConfig().getImage()).isEqualTo("mysql:8.4.3");
        assertThat(info.getConfig().getLabels().get("com.3dl.checkout.jar-test")).isEqualTo(RUN);
        assertThat(info.getHostConfig().getTmpFs()).containsEntry("/var/lib/mysql","rw,size=1g");
        assertThat(info.getMounts()).allMatch(m->"tmpfs".equals(m.getRawValues().get("Type")));
        assertThat(info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(3306))[0].getHostIp()).isEqualTo("127.0.0.1");
        Connection c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DATABASE(),@@server_uuid,VERSION()")){
            assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo(MYSQL.getDatabaseName());
            assertThat(r.getString(2)).isEqualTo(uuid);assertThat(r.getString(3)).isEqualTo("8.4.3");
        }catch(Throwable t){c.close();throw t;}
        return c;
    }

    @BeforeAll static void prepareOwnedV1() throws Exception {
        var identity=MYSQL.execInContainer("cat","/var/lib/mysql/auto.cnf");
        assertThat(identity.getExitCode()).isZero();
        uuid=identity.getStdout().lines().filter(s->s.startsWith("server-uuid=")).findFirst().orElseThrow().substring(12).trim();
        String rt="rt"+RUN.substring(0,16),mg="mg"+RUN.substring(0,16);
        String rp=UUID.randomUUID().toString(),mp=UUID.randomUUID().toString();
        try(Connection c=ownedAdmin();Statement s=c.createStatement()){
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")){r.next();assertThat(r.getInt(1)).isZero();}
            s.execute("CREATE USER '"+rt+"'@'%' IDENTIFIED BY '"+rp+"'");
            s.execute("CREATE USER '"+mg+"'@'%' IDENTIFIED BY '"+mp+"'");
            s.execute("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON "+MYSQL.getDatabaseName()+".* TO '"+mg+"'@'%'");
        }
        Map<String,String> settings=new HashMap<>();
        settings.put("CHECKOUT_V2_DB_URL","jdbc:mysql://127.0.0.1:"+MYSQL.getMappedPort(3306)+"/"+MYSQL.getDatabaseName()+"?sslMode=REQUIRED&socketTimeout=5000");
        settings.put("CHECKOUT_V2_DB_NAME",MYSQL.getDatabaseName());settings.put("CHECKOUT_V2_DB_SERVER_UUID",uuid);
        settings.put("CHECKOUT_V2_DB_USERNAME",rt);settings.put("CHECKOUT_V2_DB_PASSWORD",rp);
        settings.put("CHECKOUT_V2_MIGRATION_USERNAME",mg);settings.put("CHECKOUT_V2_MIGRATION_PASSWORD",mp);
        // Test-only schema fixture; explicit migration on the verified, owned tmpfs container.
        // The operational migration/approval protocol is separately covered by ReleaseMySqlTests.
        Flyway.configure().dataSource(ReleaseDatabase.target(settings::get).migrationSource(settings::get))
            .locations("classpath:db/checkout-v2").target("1").cleanDisabled(true)
            .baselineOnMigrate(false).createSchemas(false).load().migrate();
        try(Connection c=ownedAdmin();Statement s=c.createStatement()){
            for(String table:ReleaseDatabase.TABLES)s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+MYSQL.getDatabaseName()+"."+table+" TO '"+rt+"'@'%'");
            s.execute("GRANT SELECT ON "+MYSQL.getDatabaseName()+".flyway_schema_history TO '"+rt+"'@'%'");
        }
        settings.remove("CHECKOUT_V2_MIGRATION_USERNAME");settings.remove("CHECKOUT_V2_MIGRATION_PASSWORD");
        settings.put("CHECKOUT_QUOTE_SECRET_BASE64",Base64.getEncoder().encodeToString(java.security.SecureRandom.getSeed(32)));
        runtime=Map.copyOf(settings);
    }

    void start(Map<String,String> settings,String...arguments) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",JAR.toString()));
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(k->k.matches("(?i)^(SPRING_|CHECKOUT_|DB_|SERVER_).*|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS"));
        builder.environment().putAll(settings);
        log=directory.resolve("application.log");builder.redirectOutput(log.toFile());
        child=builder.start();child.getOutputStream().close();
    }

    String output() throws Exception {return Files.exists(log)?new String(Files.readAllBytes(log),StandardCharsets.UTF_8):"";}

    void rejectedBeforeJpa() throws Exception {
        assertThat(child.waitFor(30,TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isNotZero();
        String text=output();
        assertThat(text).doesNotContain("Tomcat initialized","Initialized JPA EntityManagerFactory","HikariPool-");
        assertThat(text).containsAnyOf("Explicit Checkout V2 startup required","Isolated development requires",
            "Required protected setting missing","Unsafe release setting","Alternate database configuration forbidden");
    }

    @ParameterizedTest @ValueSource(strings={"","default","dev","unknown","test","checkout-browser"})
    void normalJarRefusesUnqualifiedModesBeforeJpa(String profile) throws Exception {
        start(Map.of(),profile.isEmpty()?new String[0]:new String[]{"--spring.profiles.active="+profile});
        rejectedBeforeJpa();
    }

    @Test void normalJarRefusesMissingReleaseConfigurationBeforeJpa() throws Exception {
        start(Map.of(),"--spring.profiles.active=checkout-release");
        rejectedBeforeJpa();
    }

    @ParameterizedTest @ValueSource(strings={"spring.jpa.hibernate.ddl-auto=update","spring.jpa.properties.hibernate.connection.url=jdbc:forbidden:alternate"})
    void normalJarRefusesSchemaOrProviderOverrides(String setting) throws Exception {
        start(runtime,"--spring.profiles.active=checkout-release","--"+setting);
        rejectedBeforeJpa();
    }

    @Test void normalJarRunsOnlyQualifiedReleaseOnOwnedMysqlWithoutSeedOrRuntimeMigration() throws Exception {
        var settings=new HashMap<>(runtime);settings.put("SERVER_PORT","0");
        start(settings,"--spring.profiles.active=checkout-release");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<deadline && child.isAlive()){
            var match=java.util.regex.Pattern.compile("Tomcat started on port ([0-9]+)").matcher(output());
            if(match.find()){port=Integer.parseInt(match.group(1));break;}
            Thread.sleep(200);
        }
        assertThat(port).isPositive();assertThat(child.isAlive()).isTrue();
        String text=output();assertThat(text).contains("checkout-release","Initialized JPA EntityManagerFactory")
            .doesNotContain("Migrating schema","FlywayExecutor");
        {
            HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            URI base=URI.create("http://127.0.0.1:"+port);
            assertThat(http.send(HttpRequest.newBuilder(base.resolve("/")).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            var products=http.send(HttpRequest.newBuilder(base.resolve("/prodotti/all")).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(products.statusCode()).isEqualTo(200);assertThat(products.body()).isEqualTo("[]");
        }
        try(Connection c=ownedAdmin();Statement s=c.createStatement()){
            for(String table:List.of("products","orders"))try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();assertThat(r.getInt(1)).isZero();}
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        }
        try(var zip=new ZipFile(JAR.toFile())){
            assertThat(zip.getEntry("META-INF/spring.factories")).isNotNull();
            assertThat(new String(zip.getInputStream(zip.getEntry("META-INF/spring.factories")).readAllBytes(),StandardCharsets.UTF_8)).contains("CheckoutStartupEnvironment");
            assertThat(zip.stream().map(java.util.zip.ZipEntry::getName)).noneMatch(n->n.matches(".*(application-test.properties|CheckoutRuntimeJarIT.class|Tests.class|TestDatabase.class|CheckoutBrowserLauncher.class).*")
                || n.matches("BOOT-INF/lib/(flyway-|testcontainers-|junit-|spring-boot-test-|mockito-).*"));
            byte[] expected=Files.readAllBytes(Path.of("src/main/resources/db/checkout-v2/V1__create_checkout_v2.sql"));
            assertThat(zip.getInputStream(zip.getEntry("BOOT-INF/classes/db/checkout-v2/V1__create_checkout_v2.sql")).readAllBytes()).isEqualTo(expected);
        }
        for(String key:List.of("CHECKOUT_V2_DB_PASSWORD","CHECKOUT_QUOTE_SECRET_BASE64"))
            assertThat(output()).doesNotContain(runtime.get(key));
    }

    @AfterEach void stopOnlyOwnJarAndWaitForListenerClosure() throws Exception {
        if(child==null)return;
        if(child.isAlive()){child.destroy();if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();}}
        assertThat(child.isAlive()).isFalse();
        if(port>0){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean closed=false;
            while(System.nanoTime()<deadline){
                try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),300);}
                catch(java.io.IOException expected){closed=true;break;}
                Thread.sleep(200);
            }
            assertThat(closed).as("Owned JAR listener terminated").isTrue();
        }
    }
}
