package com.TreDL.ecommerce.release;

import com.TreDL.ecommerce.EcommerceApplication;
import com.TreDL.ecommerce.DataInitializer;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.*;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.ExposedPort;
import javax.sql.DataSource;
import java.sql.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ReleaseMySqlTests {
    static final String RUN=UUID.randomUUID().toString().replace("-","");
    static final Path HELPER=Path.of("tools/checkout-release/NativeStore.ps1").toAbsolutePath();
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("checkoutv2bootstrap"+RUN).withUsername("provisioner").withPassword(UUID.randomUUID().toString())
        .withLabel("com.3dl.checkout.release-test",RUN).withReuse(false).withTmpFs(Map.of("/var/lib/mysql","rw,size=1g"))
        .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(3306))));
    static String serverUuid;
    static MigrationWorkflow.Runner SQL;
    static Path launcherClasses;
    @org.junit.jupiter.api.io.TempDir Path directory;
    Map<String,String> settings;
    ReleaseDatabase.Target target;
    DataSource migration;
    FreshDatabaseMigration procedure;
    WindowsPlanStore store;
    MigrationWorkflow flow;
    byte[] v1;
    static class MutableClock extends Clock {
        Instant now=Instant.now();public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return now;}
    }
    MutableClock clock;
    @BeforeAll static void identifyAndCompile() throws Exception {
        var id=MYSQL.execInContainer("cat","/var/lib/mysql/auto.cnf");assertThat(id.getExitCode()).isZero();
        serverUuid=id.getStdout().lines().filter(l->l.startsWith("server-uuid=")).findFirst().orElseThrow().substring(12).trim();
        launcherClasses=Path.of("target/phase6d-launcher").toAbsolutePath();Files.createDirectories(launcherClasses);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-proc:none","-cp",System.getProperty("java.class.path"),"-d",launcherClasses.toString(),"tools/checkout-release/CheckoutMigration.java")).isZero();
        var loader=new java.net.URLClassLoader(new java.net.URL[]{launcherClasses.toUri().toURL()},ReleaseMySqlTests.class.getClassLoader());
        SQL=(MigrationWorkflow.Runner)Class.forName("com.TreDL.ecommerce.tools.CheckoutMigration",true,loader).getMethod("runner").invoke(null);
    }
    static Connection ownedProvisioningConnection() throws Exception {
        var info=MYSQL.getContainerInfo();assertThat(info.getConfig().getImage()).isEqualTo("mysql:8.4.3");
        assertThat(info.getConfig().getLabels().get("com.3dl.checkout.release-test")).isEqualTo(RUN);
        assertThat(info.getMounts()).allMatch(m->"tmpfs".equals(m.getRawValues().get("Type")));
        assertThat(info.getHostConfig().getTmpFs()).containsEntry("/var/lib/mysql","rw,size=1g");
        assertThat(info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(3306))[0].getHostIp()).isEqualTo("127.0.0.1");
        Connection c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DATABASE(),@@server_uuid,VERSION()")){
            assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo(MYSQL.getDatabaseName());assertThat(r.getString(2)).isEqualTo(serverUuid);assertThat(r.getString(3)).isEqualTo("8.4.3");
        }catch(Throwable e){c.close();throw e;}return c;
    }
    @BeforeEach void provisionTemporaryFixture() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");String db="checkoutv2"+suffix,rt="rt"+suffix.substring(0,16),mg="mg"+suffix.substring(0,16);
        String rp=UUID.randomUUID().toString(),mp=UUID.randomUUID().toString();
        try(Connection c=ownedProvisioningConnection();Statement s=c.createStatement()){
            s.execute("CREATE DATABASE "+db);s.execute("CREATE USER '"+rt+"'@'%' IDENTIFIED BY '"+rp+"'");s.execute("CREATE USER '"+mg+"'@'%' IDENTIFIED BY '"+mp+"'");
            s.execute("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON "+db+".* TO '"+mg+"'@'%'");
        }
        settings=new HashMap<>();settings.put("CHECKOUT_V2_DB_URL","jdbc:mysql://127.0.0.1:"+MYSQL.getMappedPort(3306)+"/"+db+"?sslMode=REQUIRED&socketTimeout=5000");
        settings.put("CHECKOUT_V2_DB_NAME",db);settings.put("CHECKOUT_V2_DB_SERVER_UUID",serverUuid);settings.put("CHECKOUT_V2_DB_USERNAME",rt);settings.put("CHECKOUT_V2_DB_PASSWORD",rp);
        settings.put("CHECKOUT_V2_MIGRATION_USERNAME",mg);settings.put("CHECKOUT_V2_MIGRATION_PASSWORD",mp);
        target=ReleaseDatabase.target(settings::get);migration=target.migrationSource(settings::get);procedure=new FreshDatabaseMigration(target,migration);
        Path root=directory.resolve("store");WindowsPlanStore.initialize(root,HELPER);store=new WindowsPlanStore(root,HELPER);
        v1=Files.readAllBytes(Path.of("src/main/resources/db/checkout-v2/V1__create_checkout_v2.sql"));clock=new MutableClock();flow=workflow(target,migration,v1,SQL);
    }
    MigrationWorkflow workflow(ReleaseDatabase.Target t,DataSource ds,byte[] script,MigrationWorkflow.Runner runner){return new MigrationWorkflow(t,ds,store,script,clock,"10.20.1",runner);}
    String approved()throws Exception{String id=flow.preflight();flow.approve(id);return id;}
    void grantRuntime()throws Exception{
        try(Connection c=ownedProvisioningConnection();Statement s=c.createStatement()){
            for(String table:ReleaseDatabase.TABLES)s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+target.database+".`"+table+"` TO '"+target.username+"'@'%'");
            s.execute("GRANT SELECT ON "+target.database+".flyway_schema_history TO '"+target.username+"'@'%'");
        }
    }
    int tables()throws Exception{try(Connection c=migration.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")){r.next();return r.getInt(1);}}
    void migrated()throws Exception{flow.migrate(approved());grantRuntime();}
    void terminateOwnedLease()throws Exception{
        try(Connection c=ownedProvisioningConnection();PreparedStatement q=c.prepareStatement("SELECT IS_USED_LOCK(?)")){
            q.setString(1,"3dl-v1:"+target.database);long session;try(ResultSet r=q.executeQuery()){assertThat(r.next()).isTrue();session=r.getLong(1);assertThat(session).isPositive();}
            try(Statement s=c.createStatement()){s.execute("KILL CONNECTION "+session);}
        }
    }
    @Test void runtimeRefusesAnEmptySchemaWithoutApprovedMigrationHistory(){var e=new org.springframework.mock.env.MockEnvironment();settings.forEach(e::setProperty);assertThatThrownBy(()->new ReleaseConfiguration().dataSource(e)).hasMessage("Verified V1 schema and runtime privileges required before startup");}
    @Test void preflightDoesNotExecuteDdlAndRuntimeCannotCreateTables()throws Exception{
        procedure.preflight();assertThat(tables()).isZero();
        try(Connection c=target.runtimePreflightSource().getConnection();Statement s=c.createStatement()){assertThatThrownBy(()->s.execute("CREATE TABLE "+target.database+".forbidden(id INT)")).isInstanceOf(SQLException.class);}
        assertThat(tables()).isZero();
    }
    @Test void v1MigrationThenRealReleaseSpringStartupUsesValidateWithoutSeedingOrFlyway()throws Exception{
        String id=approved();flow.migrate(id);assertThatThrownBy(()->flow.verify(id)).isInstanceOf(SQLException.class);grantRuntime();flow.verify(id);assertThat(tables()).isEqualTo(7);
        settings.remove("CHECKOUT_V2_MIGRATION_USERNAME");settings.remove("CHECKOUT_V2_MIGRATION_PASSWORD");settings.put("CHECKOUT_QUOTE_SECRET_BASE64",Base64.getEncoder().encodeToString(new byte[32]));
        var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);env.getPropertySources().addFirst(new MapPropertySource("isolated-release",new HashMap<>(settings)));
        var app=new SpringApplication(EcommerceApplication.class);app.setEnvironment(env);app.setLogStartupInfo(false);
        try(ConfigurableApplicationContext context=app.run("--spring.profiles.active=checkout-release","--server.address=127.0.0.1","--server.port=0")){
            assertThat(context.isActive()).isTrue();assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("checkout-release");assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(context.getBeansOfType(DataInitializer.class)).isEmpty();assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            try(Connection c=context.getBean(DataSource.class).getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM products")){r.next();assertThat(r.getInt(1)).isZero();}
        }
    }
    @Test void nonEmptyDatabaseStopsBeforeMigrationCallback()throws Exception{try(Connection c=migration.getConnection();Statement s=c.createStatement()){s.execute("CREATE TABLE marker(id INT)");}assertThatThrownBy(flow::preflight).hasMessageContaining("empty schema");assertThat(tables()).isEqualTo(1);}
    @Test void wrongServerUuidIsRejectedBeforeDdl(){settings.put("CHECKOUT_V2_DB_SERVER_UUID","00000000-0000-0000-0000-000000000000");var wrong=ReleaseDatabase.target(settings::get);assertThatThrownBy(()->new FreshDatabaseMigration(wrong,wrong.migrationSource(settings::get)).preflight()).isInstanceOf(SQLException.class);}
    @Test void runtimeDdlGrantIsRejected()throws Exception{try(Connection c=ownedProvisioningConnection();Statement s=c.createStatement()){s.execute("GRANT CREATE ON "+target.database+".* TO '"+target.username+"'@'%'");}assertThatThrownBy(procedure::preflight).isInstanceOf(SQLException.class);assertThat(tables()).isZero();}
    @Test void crossDatabaseRuntimeGrantIsRejected()throws Exception{try(Connection c=ownedProvisioningConnection();Statement s=c.createStatement()){s.execute("GRANT SELECT ON "+MYSQL.getDatabaseName()+".* TO '"+target.username+"'@'%'");}assertThatThrownBy(procedure::preflight).isInstanceOf(SQLException.class);}
    @Test void rootCannotBeUsedAsMigrationAccount(){settings.put("CHECKOUT_V2_MIGRATION_USERNAME","root");settings.put("CHECKOUT_V2_MIGRATION_PASSWORD",MYSQL.getPassword());assertThatThrownBy(()->new FreshDatabaseMigration(target,target.migrationSource(settings::get)).preflight()).isInstanceOf(SQLException.class);}
    @Test void historyReallyRejectsAllWritesAndRuntimeDdl()throws Exception{
        migrated();try(Connection c=target.runtimeSource().getConnection();Statement s=c.createStatement()){
            for(String sql:List.of("UPDATE flyway_schema_history SET success=0 WHERE installed_rank=-999","DELETE FROM flyway_schema_history WHERE installed_rank=-999","INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) VALUES(999,'999','audit','SQL','audit.sql','audit',0,1)","CREATE TABLE forbidden(id INT)"))
                assertThatThrownBy(()->s.execute(sql)).isInstanceOf(SQLException.class);
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='1'")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
        }
    }
    @Test void overbroadHistoryAndGlobalGrantsAreRejected()throws Exception{
        migrated();try(Connection c=ownedProvisioningConnection();Statement s=c.createStatement()){s.execute("GRANT UPDATE ON "+target.database+".flyway_schema_history TO '"+target.username+"'@'%'");}
        assertThatThrownBy(()->target.runtimeSource().getConnection()).isInstanceOf(SQLException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true}) void activeAndActivatableRolesAreRejected(boolean active)throws Exception{
        String role="role"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        try(Connection c=ownedProvisioningConnection();Statement s=c.createStatement()){s.execute("CREATE ROLE '"+role+"'");s.execute("GRANT SELECT ON "+target.database+".* TO '"+role+"'");s.execute("GRANT '"+role+"' TO '"+target.username+"'@'%'");if(active)s.execute("SET DEFAULT ROLE '"+role+"' TO '"+target.username+"'@'%'");}
        assertThatThrownBy(procedure::preflight).isInstanceOf(SQLException.class);
    }
    @Test void planRequiresTrustedApprovalAndIsSingleUse()throws Exception{
        String id=flow.preflight();assertThatThrownBy(()->flow.migrate(id)).isInstanceOf(IllegalStateException.class);assertThat(tables()).isZero();
        flow.approve(id);flow.migrate(id);assertThatThrownBy(()->flow.migrate(id)).isInstanceOf(IllegalStateException.class);assertThat(tables()).isEqualTo(7);
    }
    @Test void expiredPlanIsRejectedWithoutDdl()throws Exception{String id=approved();clock.now=clock.now.plusSeconds(901);assertThatThrownBy(()->flow.migrate(id)).hasMessage("Plan expired");assertThat(tables()).isZero();}
    @Test void changedHostWithSameDatabaseIsRejectedBeforeConnection()throws Exception{
        String id=approved();settings.put("CHECKOUT_V2_DB_URL","jdbc:mysql://other.example.invalid:"+target.configuredPort()+"/"+target.database+"?sslMode=VERIFY_IDENTITY");var b=ReleaseDatabase.target(settings::get);
        assertThatThrownBy(()->workflow(b,b.migrationSource(settings::get),v1,SQL).migrate(id)).isInstanceOf(IllegalStateException.class);assertThat(tables()).isZero();assertThat(store.exists("used",id)).isTrue();
    }
    @Test void changedUuidAndV1AreRejected()throws Exception{
        String id=approved();byte[] changed=v1.clone();changed[0]^=1;
        assertThatThrownBy(()->workflow(target,migration,changed,SQL).migrate(id)).isInstanceOf(IllegalStateException.class);assertThat(tables()).isZero();
        assertThatThrownBy(()->flow.migrate(id)).isInstanceOf(IllegalStateException.class);
    }
    @Test void changedUuidAfterPreflightIsRejected()throws Exception{
        String id=approved();settings.put("CHECKOUT_V2_DB_SERVER_UUID","00000000-0000-0000-0000-000000000000");var b=ReleaseDatabase.target(settings::get);
        assertThatThrownBy(()->workflow(b,b.migrationSource(settings::get),v1,SQL).migrate(id)).isInstanceOf(IllegalStateException.class);assertThat(tables()).isZero();
    }
    @Test void lostLockBeforeFirstDdlStopsAndPersistsFailure()throws Exception{
        String id=approved();var stopped=workflow(target,migration,v1,new MigrationWorkflow.Runner(){public void migrate(DataSource ds,byte[] script){try{terminateOwnedLease();try(Connection c=ds.getConnection();Statement s=c.createStatement()){s.execute("CREATE TABLE forbidden(id INT)");}}catch(Exception e){throw new IllegalStateException(e);}}public void validate(DataSource ds,byte[] script){}});
        assertThatThrownBy(()->stopped.migrate(id)).isInstanceOf(IllegalStateException.class);assertThat(tables()).isZero();assertThat(store.exists("failure",WindowsPlanStore.resource(target.serverUuid,target.database))).isTrue();
        assertThatThrownBy(flow::preflight).isInstanceOf(IllegalStateException.class);
    }
    @Test void lostLockDuringPartialMigrationBlocksNextSqlAndRetry()throws Exception{
        String id=approved();var stopped=workflow(target,migration,v1,new MigrationWorkflow.Runner(){public void migrate(DataSource ds,byte[] script){try(Connection c=ds.getConnection();Statement s=c.createStatement()){
                        s.execute("CREATE TABLE partial(id INT)");
            assertThat(c.getMetaData().getConnection()).isSameAs(c);
            assertThatThrownBy(()->c.unwrap(Connection.class)).isInstanceOf(SQLException.class);
            try(ResultSet rows=s.executeQuery("SELECT 1")){
                Statement fromResult=rows.getStatement();assertThat(fromResult).isSameAs(s);
                terminateOwnedLease();assertThatThrownBy(()->fromResult.execute("CREATE TABLE forbidden(id INT)")).isInstanceOf(SQLException.class);
            }
        }catch(Exception e){throw new IllegalStateException(e);}}public void validate(DataSource ds,byte[] script){}});
        assertThatThrownBy(()->stopped.migrate(id)).isInstanceOf(IllegalStateException.class);assertThat(tables()).isEqualTo(1);assertThatThrownBy(()->flow.migrate(id)).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentMigratorsCannotBothEnterCallback()throws Exception{
        String id=approved();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);var workers=Executors.newSingleThreadExecutor();
        var held=workflow(target,migration,v1,new MigrationWorkflow.Runner(){public void migrate(DataSource ds,byte[] script){entered.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){throw new IllegalStateException(e);}SQL.migrate(ds,script);}public void validate(DataSource ds,byte[] script){SQL.validate(ds,script);}});
        try{var first=workers.submit(()->{held.migrate(id);return null;});assertThat(entered.await(60,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->flow.migrate(id)).isInstanceOf(IllegalStateException.class);release.countDown();first.get(60,TimeUnit.SECONDS);assertThat(tables()).isEqualTo(7);}finally{release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void standaloneCommandPreflightMigrateAndVerifyUseOnlyApprovedV1()throws Exception{
        String cp=Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).filter(p->!p.replace('\\','/').contains("target/test-classes") && !p.replace('\\','/').contains("src/test")).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        String pre=command(cp,"Preflight",null,0);String id=pre.substring(pre.indexOf("PLAN ")+5).substring(0,64);
        command(cp,"Migrate",id,1);assertThat(tables()).isZero();command(cp,"Approve",id,0);command(cp,"Migrate",id,0);assertThat(tables()).isEqualTo(7);
        command(cp,"Verify",id,1);grantRuntime();command(cp,"Verify",id,0);command(cp,"Migrate",id,1);assertThat(tables()).isEqualTo(7);
    }
    @Test void globalPrivilegesAreRejectedEvenWithOtherwiseValidRuntimeGrants()throws Exception{
        migrated();try(Connection c=ownedProvisioningConnection();Statement s=c.createStatement()){s.execute("GRANT SELECT ON *.* TO '"+target.username+"'@'%'");}
        assertThatThrownBy(()->target.runtimeSource().getConnection()).isInstanceOf(SQLException.class);
    }
    @Test void destinationPortChangedAfterApprovalIsRejectedWithoutDdl()throws Exception{
        String id=approved();settings.put("CHECKOUT_V2_DB_URL",settings.get("CHECKOUT_V2_DB_URL").replace(":"+target.configuredPort()+"/",":"+(target.configuredPort()+1)+"/"));var other=ReleaseDatabase.target(settings::get);
        assertThatThrownBy(()->workflow(other,other.migrationSource(settings::get),v1,SQL).migrate(id)).isInstanceOf(IllegalStateException.class);assertThat(tables()).isZero();
    }
    @Test void interruptedProcessLeavesDurableConsumedAndUncertainState()throws Exception{
        String id=approved();var args=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),InterruptedMigrationWorker.class.getName(),directory.resolve("store").toString(),HELPER.toString(),id,Path.of("src/main/resources/db/checkout-v2/V1__create_checkout_v2.sql").toAbsolutePath().toString());
        var builder=new ProcessBuilder(args).redirectErrorStream(true);builder.environment().putAll(settings);
        Process child=builder.start();var reader=Executors.newSingleThreadExecutor();
        try{
            var ready=reader.submit(()->{var in=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));String line;while((line=in.readLine())!=null)if(line.equals("PARTIAL_READY"))return true;return false;});
            assertThat(ready.get(90,TimeUnit.SECONDS)).isTrue();child.destroyForcibly();assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();
            assertThat(store.exists("used",id)).isTrue();String resource=WindowsPlanStore.resource(target.serverUuid,target.database);
            assertThat(store.exists("start",resource)).isTrue();assertThat(store.exists("success",resource)).isFalse();assertThat(store.exists("failure",resource)).isFalse();assertThat(tables()).isEqualTo(1);
            assertThatThrownBy(flow::preflight).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->flow.migrate(id)).isInstanceOf(IllegalStateException.class);
        }finally{if(child.isAlive())child.destroyForcibly();reader.shutdownNow();}
    }
    String command(String cp,String action,String id,int expected)throws Exception{
        var args=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",launcherClasses+java.io.File.pathSeparator+cp,"com.TreDL.ecommerce.tools.CheckoutMigration",action,Path.of("src/main/resources/db/checkout-v2/V1__create_checkout_v2.sql").toAbsolutePath().toString(),directory.resolve("store").toString(),HELPER.toString()));if(id!=null)args.add(id);
        var b=new ProcessBuilder(args).redirectErrorStream(true);b.environment().keySet().removeIf(k->k.matches("^(SPRING_|CHECKOUT_|DB_).*|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS"));b.environment().putAll(settings);
        Process p=b.start();try{assertThat(p.waitFor(120,TimeUnit.SECONDS)).isTrue();String out=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(out).doesNotContain(settings.get("CHECKOUT_V2_DB_PASSWORD"),settings.get("CHECKOUT_V2_MIGRATION_PASSWORD"));assertThat(p.exitValue()).describedAs(out).isEqualTo(expected);return out;
        }finally{if(p.isAlive())p.destroyForcibly();}
    }
}
