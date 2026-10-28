package com.TreDL.ecommerce.tools;

import com.TreDL.ecommerce.EcommerceApplication;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.*;
import javax.sql.DataSource;
import java.io.*;
import java.lang.reflect.*;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** Local-only browser supervisor. No Spring test classes or test resources on its classpath. */
public final class CheckoutBrowserLauncher {
    /** Present only in the isolated launcher classpath, never in the application JAR. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    public static class DevelopmentBootstrap {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMAGE = "mysql:8.4.3";
    private static final String RUN_LABEL = "com.3dl.checkout.browser.run";
    private static final String OWNER_LABEL = "com.3dl.checkout.browser.owner";
    private static final Map<String,String> CONFIG = Map.ofEntries(
        Map.entry("server.address","127.0.0.1"), Map.entry("server.port","9085"),
        Map.entry("server.shutdown","graceful"), Map.entry("spring.lifecycle.timeout-per-shutdown-phase","15s"),
        Map.entry("spring.jpa.hibernate.ddl-auto","validate"),
        Map.entry("spring.jpa.properties.hibernate.hbm2ddl.auto","validate"), Map.entry("spring.jpa.generate-ddl","false"),
        Map.entry("spring.sql.init.mode","never"), Map.entry("spring.jpa.open-in-view","false"),
        Map.entry("spring.jpa.show-sql","false"), Map.entry("spring.flyway.enabled","false"),
        Map.entry("spring.h2.console.enabled","false"), Map.entry("app.data-initializer.enabled","true"),
        Map.entry("checkout.quote-secret-base64","${CHECKOUT_QUOTE_SECRET_BASE64}"),
        Map.entry("server.servlet.session.cookie.http-only","true"),
        Map.entry("server.servlet.session.cookie.same-site","lax"),
        Map.entry("server.servlet.session.cookie.secure","false"),
        Map.entry("server.servlet.session.persistent","false"),
        Map.entry("spring.main.banner-mode","off"), Map.entry("logging.level.root","OFF"));
    private final Path root, directory;
    private final String run, owner, database, user = "browser_user", password = randomSecret(), rootPassword = randomSecret();
    private String id = "", uuid = "", url;
    private int dbPort;
    private volatile ConfigurableApplicationContext application;
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile String phase = "STARTING";

    private CheckoutBrowserLauncher(Path root, String run) throws Exception {
        this.root = root.toRealPath();
        require(run.matches("[a-f0-9]{32}"), "Invalid RunId");
        this.run = run;
        owner = ownerHash(this.root);
        database = "checkout_browser_" + run;
        directory = this.root.resolve("target/checkout-browser").resolve(run);
        require(Files.isDirectory(directory) && !Files.isSymbolicLink(directory)
                && directory.toRealPath().equals(directory), "Missing or redirected run directory");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("check")) {
            staticChecks(Path.of(args[1]), Path.of(args[2]));
            return; // This branch cannot invoke Docker, JDBC, Flyway or Spring.
        }
        require(args.length == 4 && args[0].equals("serve"), "Expected serve ROOT RUNID CONFIG");
        Path root = Path.of(args[1]).toRealPath(), config = Path.of(args[3]).toRealPath();
        validateConfig(config); validateClasspath(root);
        require(config.equals(root.resolve("tools/checkout-browser/browser-checkout.properties")), "Unexpected config path");
        String hmac = System.getenv("CHECKOUT_QUOTE_SECRET_BASE64");
        require(hmac != null && Base64.getDecoder().decode(hmac).length == 32, "Temporary HMAC key required");
        CheckoutBrowserLauncher launcher = new CheckoutBrowserLauncher(root,args[2]);
        // No credentials in arguments, manifests or logs, including startup exceptions.
        System.setOut(new PrintStream(new RedactedLines(System.out,List.of(hmac,launcher.password,launcher.rootPassword)),true,StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new RedactedLines(System.err,List.of(hmac,launcher.password,launcher.rootPassword)),true,StandardCharsets.UTF_8));
        Runtime.getRuntime().addShutdownHook(new Thread(launcher::close,"checkout-browser-shutdown"));
        try { launcher.serve(config); }
        catch (Throwable failure) {
            launcher.phase = "FAILED"; launcher.state();
            System.err.println("Browser startup/runtime failed; category=" + failure.getClass().getSimpleName());
        } finally { launcher.close(); }
    }
    private void serve(Path config) throws Exception {
        require(state(),"Cannot record run ownership");
        require(docker(List.of("context","inspect","desktop-linux","--format","{{.Endpoints.docker.Host}}"),Map.of()).output.trim()
            .equals("npipe:////./pipe/dockerDesktopLinuxEngine"), "Only the local Docker Desktop Linux daemon is allowed");
        require(docker(List.of("version","--format","{{.Server.Version}}"),Map.of()).exit==0,"Local daemon unavailable");
        try (ServerSocket port = new ServerSocket()) { port.bind(new InetSocketAddress("127.0.0.1",9085)); }
        Map<String,String> credentials = Map.of("MYSQL_DATABASE",database,"MYSQL_USER",user,
                "MYSQL_PASSWORD",password,"MYSQL_ROOT_PASSWORD",rootPassword);
        Result created = docker(List.of("run","--detach","--rm","--restart=no",
            "--label",RUN_LABEL+"="+run,"--label",OWNER_LABEL+"="+owner,
            "--tmpfs","/var/lib/mysql:rw,size=1g","--publish","127.0.0.1::3306",
            "--env","MYSQL_DATABASE","--env","MYSQL_USER","--env","MYSQL_PASSWORD","--env","MYSQL_ROOT_PASSWORD",IMAGE),credentials);
        require(created.exit==0,"Could not create owned MySQL container");
        id=created.output.trim(); require(id.matches("[a-f0-9]{64}"),"Unexpected container ID");
        require(state(),"Cannot record owned container");
        JsonNode info=inspectOwned();
        dbPort=info.at("/NetworkSettings/Ports/3306~1tcp/0/HostPort").asInt();
        require(dbPort>0 && dbPort!=3306,"Unexpected database port");
        url="jdbc:mysql://127.0.0.1:"+dbPort+"/"+database+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=15000";
        state();
        DriverManagerDataSource delegate=new DriverManagerDataSource(url,user,password);
        delegate.setDriverClassName("com.mysql.cj.jdbc.Driver");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        boolean ready=false;
        while(System.nanoTime()<deadline) {
            require(!stopRequested(),"Stop requested during startup");
            Result identity=docker(List.of("exec",id,"cat","/var/lib/mysql/auto.cnf"),Map.of());
            Matcher matcher=Pattern.compile("(?m)^server-uuid\\s*=\\s*([a-fA-F0-9-]+)\\s*$").matcher(identity.output);
            if(identity.exit==0 && matcher.find()) {
                uuid=matcher.group(1);
                try(Connection connection=delegate.getConnection()) { assertIdentity(connection); ready=true; break; }
                catch(SQLException notReady) { /* Temporary server is still initializing; never use another destination. */ }
            }
            Thread.sleep(500);
        }
        require(ready,"Owned MySQL did not become ready"); require(state(),"Cannot record server identity");
        GuardedSource source=new GuardedSource(delegate);
        try(Connection connection=source.getConnection(); Statement statement=connection.createStatement();
            ResultSet tables=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            require(tables.next() && tables.getLong(1)==0,"Owned schema must be empty");
        }
        Flyway.configure().dataSource(source).locations("classpath:db/checkout-v2")
            .cleanDisabled(true).baselineOnMigrate(false).createSchemas(false).load().migrate();
        require(!stopRequested(),"Stop requested before Spring startup");
        SpringApplication boot=new SpringApplication(EcommerceApplication.class, DevelopmentBootstrap.class);
        boot.addInitializers(context -> {
            require(Arrays.equals(context.getEnvironment().getActiveProfiles(),new String[]{"checkout-browser"}),"Unexpected profiles");
            for(String key:List.of("spring.datasource.url","spring.datasource.username","spring.datasource.password","spring.datasource.driver-class-name"))
                require(context.getEnvironment().getProperty(key)==null,"External datasource configuration is forbidden");
            GenericApplicationContext generic=(GenericApplicationContext)context;
            generic.registerBeanDefinition("dataSource",BeanDefinitionBuilder.genericBeanDefinition(GuardedSource.class,()->source).getBeanDefinition());
            // Pin invariants above all other property sources. Secrets stay in environment/memory.
            Map<String,Object> pinned=new HashMap<>(CONFIG);
            pinned.put("checkout.quote-secret-base64",System.getenv("CHECKOUT_QUOTE_SECRET_BASE64"));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-browser-invariants",pinned));
        });
        application=boot.run("--spring.config.location="+config.toUri(),"--spring.profiles.active=checkout-browser");
        phase="READY"; require(state(),"Cannot record READY state");
        System.out.println("READY RunId="+run+" URL=http://127.0.0.1:9085");
        while(application.isActive() && !stopRequested()) Thread.sleep(250);
    }
    private boolean stopRequested() throws Exception {
        Path signal=directory.resolve("stop.request");
        if(!Files.exists(signal)) return false;
        require(Files.isRegularFile(signal) && !Files.isSymbolicLink(signal)
                && Files.readString(signal,StandardCharsets.UTF_8).equals(run),"Unowned stop request");
        return true;
    }
    private JsonNode inspectOwned() throws Exception {
        Result inspected=docker(List.of("inspect",id),Map.of());
        require(inspected.exit==0,"Owned container cannot be inspected");
        JsonNode info=JSON.readTree(inspected.output).get(0);
        validateOwnership(info,id,run,owner); return info;
    }
    static void validateOwnership(JsonNode info,String id,String run,String owner) {
        require(info.path("Id").asText().equals(id) && id.matches("[a-f0-9]{64}"),"Wrong container ID");
        require(info.at("/Config/Image").asText().equals(IMAGE),"Wrong image");
        require(info.at("/Config/Labels/"+RUN_LABEL).asText().equals(run),"Wrong run label");
        require(info.at("/Config/Labels/"+OWNER_LABEL).asText().equals(owner),"Wrong owner label");
        require(info.at("/HostConfig/Tmpfs/~1var~1lib~1mysql").asText().equals("rw,size=1g"),"Wrong storage");
        for(JsonNode mount:info.path("Mounts")) require(mount.path("Type").asText().equals("tmpfs"),"Persistent storage forbidden");
        JsonNode ports=info.at("/HostConfig/PortBindings/3306~1tcp");
        require(ports.isArray() && ports.size()==1 && ports.get(0).path("HostIp").asText().equals("127.0.0.1"),"Non-loopback database binding");
    }
    private void assertIdentity(Connection connection) throws SQLException {
        if(!url.split("\\?",2)[0].equals(connection.getMetaData().getURL().split("\\?",2)[0])) throw new SQLException("Wrong JDBC destination");
        try(Statement check=connection.createStatement(); ResultSet rs=check.executeQuery("SELECT DATABASE(),@@server_uuid,VERSION()")) {
            if(!rs.next() || !database.equals(rs.getString(1)) || !uuid.equals(rs.getString(2)) || !rs.getString(3).equals("8.4.3"))
                throw new SQLException("Wrong database identity");
        }
    }
    private final class GuardedSource extends AbstractDataSource {
        private final DataSource delegate;
        GuardedSource(DataSource delegate) { this.delegate=delegate; }
        @Override public Connection getConnection() throws SQLException {
            try { inspectOwned(); } catch(Exception e) { throw new SQLException("Container ownership could not be verified"); }
            Connection raw=delegate.getConnection();
            try { assertIdentity(raw); return guardedConnection(raw); }
            catch(SQLException e) { raw.close(); throw e; }
        }
        @Override public Connection getConnection(String user,String password) throws SQLException { throw new SQLException("External credentials forbidden"); }
    }
    private Connection guardedConnection(Connection raw) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("unwrap")) { if(((Class<?>)args[0]).isInstance(proxy)) return proxy; throw new SQLException("Native unwrap forbidden"); }
            if(name.equals("isWrapperFor")) return ((Class<?>)args[0]).isInstance(proxy);
            if(name.equals("setCatalog")) require(database.equals(args[0]),"Catalog switch forbidden");
            if(name.equals("commit")) assertIdentity(raw);
            if(Set.of("createStatement","prepareStatement","prepareCall").contains(name)) {
                assertIdentity(raw);
                Statement statement=(Statement)invoke(method,raw,args);
                String prepared=args!=null && args.length>0 && args[0] instanceof String?(String)args[0]:null;
                Class<?> api=statement instanceof CallableStatement?CallableStatement.class:statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{api},(p,m,a)->{
                    if(m.getName().equals("getConnection")) return proxy;
                    if(m.getName().equals("unwrap")) throw new SQLException("Native unwrap forbidden");
                    if(m.getName().startsWith("execute") || m.getName().equals("addBatch")) {
                        assertIdentity(raw);
                        String sql=a!=null && a.length>0 && a[0] instanceof String?(String)a[0]:prepared;
                        require(sql==null || !Pattern.compile("(?is)^\\s*(?:USE\\b|(?:DROP|CREATE)\\s+(?:DATABASE|SCHEMA)\\b)").matcher(sql).find(),"Database switching or deletion forbidden");
                    }
                    return invoke(m,statement,a);
                });
            }
            return invoke(method,raw,args);
        });
    }
    private static Object invoke(Method method,Object object,Object[] args) throws Throwable {
        try { return method.invoke(object,args); } catch(InvocationTargetException e) { throw e.getCause(); }
    }
    private synchronized void close() {
        if(!closing.compareAndSet(false,true)) return;
        phase="STOPPING"; state();
        try {
            if(application!=null) application.close(); // Database must outlive Spring.
            if(id.isEmpty()) {
                Result found=docker(List.of("ps","-aq","--filter","label="+RUN_LABEL+"="+run,"--filter","label="+OWNER_LABEL+"="+owner),Map.of());
                require(found.exit==0,"Cannot discover owned resources");
                String[] matches=found.output.trim().isEmpty()?new String[0]:found.output.trim().split("\\s+");
                require(matches.length<=1,"Ambiguous owned resources");
                if(matches.length==1) {
                    Result full=docker(List.of("inspect","--format","{{.Id}}",matches[0]),Map.of());
                    require(full.exit==0,"Cannot resolve owned container"); id=full.output.trim();
                }
            }
            if(!id.isEmpty()) {
                Result exists=docker(List.of("ps","-aq","--filter","id="+id),Map.of());
                require(exists.exit==0,"Cannot verify cleanup");
                if(!exists.output.trim().isEmpty()) {
                    JsonNode owned = inspectOwned();
                    if(!uuid.isEmpty() && owned.at("/State/Running").asBoolean()) {
                        Result identity=docker(List.of("exec",id,"cat","/var/lib/mysql/auto.cnf"),Map.of());
                        require(identity.exit==0 && identity.output.contains("server-uuid="+uuid),"Server identity changed before cleanup");
                    }
                    List<String> removal=owned.at("/State/Running").asBoolean()
                        ? List.of("stop","--time","15",id) : List.of("rm","--volumes",id);
                    require(docker(removal,Map.of()).exit==0,"Owned container cleanup failed");
                }
            }
            phase="STOPPED";
        } catch(Throwable failure) { phase="CLEANUP_REQUIRED"; System.err.println("Owned cleanup incomplete; use Stop with this RunId when Docker is available."); }
        state();
    }
    private record Result(int exit,String output) {}
    private static Result docker(List<String> arguments,Map<String,String> environment) throws Exception {
        List<String> command=new ArrayList<>(List.of("docker","--context","desktop-linux")); command.addAll(arguments);
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true); builder.environment().putAll(environment);
        Process child=builder.start();
        var output=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return child.getInputStream().readAllBytes(); }
            catch(IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        long timeout=arguments.get(0).equals("run")?180:30;
        if(!child.waitFor(timeout,java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly(); // Only the Docker CLI process just created here; never another process/container.
            throw new IOException("Owned Docker operation timed out");
        }
        return new Result(child.exitValue(),new String(output.get(5,java.util.concurrent.TimeUnit.SECONDS),StandardCharsets.UTF_8));
    }
    private boolean state() {
        try {
            Map<String,Object> state=new LinkedHashMap<>();
            state.put("runId",run); state.put("owner",owner); state.put("pid",ProcessHandle.current().pid());
            state.put("started",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());
            state.put("phase",phase); state.put("containerId",id); state.put("serverUuid",uuid); state.put("database",database);
            state.put("webPort",9085); state.put("dbPort",dbPort);
            Path tmp=Files.createTempFile(directory,"state-",".tmp"); Files.writeString(tmp,JSON.writeValueAsString(state),StandardCharsets.UTF_8);
            Files.move(tmp,directory.resolve("state.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch(Exception failure) { System.err.println("Could not update owned state manifest."); return false; }
    }
    private static String randomSecret() { byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String ownerHash(Path root) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(root.toRealPath().toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))); }
    static void require(boolean valid,String message) { if(!valid) throw new IllegalStateException(message); }
    private static void validateConfig(Path config) throws Exception {
        Properties properties=new Properties();
        try(Reader reader=Files.newBufferedReader(config,StandardCharsets.UTF_8)) { properties.load(reader); }
        // Tolerate an editor BOM only on the first key.
        if(properties.containsKey("\ufeffserver.address")) properties.put("server.address",properties.remove("\ufeffserver.address"));
        require(properties.size()==CONFIG.size(),"Unexpected configuration entries");
        for(var entry:CONFIG.entrySet()) require(entry.getValue().equals(properties.getProperty(entry.getKey())),"Unexpected configuration: "+entry.getKey());
    }
    private static void validateClasspath(Path root) throws Exception {
        Path testClasses=root.resolve("target/test-classes").toAbsolutePath();
        for(String entry:System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            Path path=Path.of(entry).toAbsolutePath().normalize();
            require(!path.startsWith(testClasses) && !path.startsWith(root.resolve("src/test")),"Project test classpath is forbidden");
        }
        require(CheckoutBrowserLauncher.class.getClassLoader().getResource("application-test.properties")==null,"Test resources leaked into classpath");
        require(CheckoutBrowserLauncher.class.getClassLoader().getResource("com/TreDL/ecommerce/service/CheckoutIntegrationTests.class")==null,"Project test classes leaked into classpath");
        require(Flyway.class.getProtectionDomain().getCodeSource().getLocation()!=null,"Flyway dependency missing");
    }
    static final class RedactedLines extends OutputStream {
        private final OutputStream output; private final List<String> secrets; private final ByteArrayOutputStream line=new ByteArrayOutputStream();
        RedactedLines(OutputStream output,List<String> secrets) { this.output=output; this.secrets=secrets; }
        @Override public synchronized void write(int value) throws IOException {
            line.write(value);
            if(value=='\n') { String text=line.toString(StandardCharsets.UTF_8); for(String secret:secrets) text=text.replace(secret,"[REDACTED]"); output.write(text.getBytes(StandardCharsets.UTF_8)); line.reset(); }
        }
        @Override public void flush() throws IOException { output.flush(); } // Never expose partial secrets across writes/flushes.
    }
    private static void staticChecks(Path root,Path config) throws Exception {
        validateConfig(config); validateClasspath(root.toRealPath());
        ByteArrayOutputStream output=new ByteArrayOutputStream(); RedactedLines redactor=new RedactedLines(output,List.of("fixture-secret"));
        redactor.write("prefix fixture-".getBytes(StandardCharsets.UTF_8)); redactor.flush();
        redactor.write("secret suffix\n".getBytes(StandardCharsets.UTF_8)); redactor.flush();
        require(output.toString(StandardCharsets.UTF_8).equals("prefix [REDACTED] suffix\n"),"Secret redaction failed");
        String run="a".repeat(32), owner="b".repeat(64), id="c".repeat(64);
        JsonNode info=JSON.readTree("{\"Id\":\""+id+"\",\"Config\":{\"Image\":\"mysql:8.4.3\",\"Labels\":{\""+RUN_LABEL+"\":\""+run+"\",\""+OWNER_LABEL+"\":\""+owner+"\"}},\"HostConfig\":{\"PortBindings\":{\"3306/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"0\"}]},\"Tmpfs\":{\"/var/lib/mysql\":\"rw,size=1g\"}},\"NetworkSettings\":{\"Ports\":{\"3306/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"49000\"}]}}}");
        validateOwnership(info,id,run,owner);
        for(String wrong:List.of("0.0.0.0","another-owner","another-run","persistent-mount")) {
            JsonNode changed=info.deepCopy();
            if(wrong.equals("0.0.0.0")) ((com.fasterxml.jackson.databind.node.ObjectNode)changed.at("/HostConfig/PortBindings/3306~1tcp/0")).put("HostIp",wrong);
            else if(wrong.equals("persistent-mount")) ((com.fasterxml.jackson.databind.node.ObjectNode)changed).putArray("Mounts").addObject().put("Type","volume");
            else ((com.fasterxml.jackson.databind.node.ObjectNode)changed.at("/Config/Labels")).put(wrong.equals("another-owner")?OWNER_LABEL:RUN_LABEL,wrong);
            boolean rejected=false; try { validateOwnership(changed,id,run,owner); } catch(IllegalStateException expected) { rejected=true; }
            require(rejected,"Foreign resource was accepted");
        }
        String fixtureRun=UUID.randomUUID().toString().replace("-", "");
        Path fixtureDirectory=root.toRealPath().resolve("target/checkout-browser").resolve(fixtureRun);
        Files.createDirectories(fixtureDirectory);
        try {
            CheckoutBrowserLauncher fixture=new CheckoutBrowserLauncher(root,fixtureRun);
            require(fixture.state(),"State manifest persistence failed");
            String manifest=Files.readString(fixtureDirectory.resolve("state.json"));
            require(!manifest.contains(fixture.password) && !manifest.contains(fixture.rootPassword)
                    && !manifest.contains("CHECKOUT_QUOTE_SECRET_BASE64"),"Sensitive state leaked");
            require(JSON.readTree(manifest).path("owner").asText().equals(ownerHash(root)),"Manifest ownership mismatch");
        } finally {
            Files.deleteIfExists(fixtureDirectory.resolve("state.json"));
            Files.delete(fixtureDirectory); // Only the empty fixture directory just created by this check.
        }
        System.out.println("STATIC CHECKS PASSED: configuration, classpath, secret redaction, owned resource validation, non-sensitive atomic manifest. No services started.");
    }
}
