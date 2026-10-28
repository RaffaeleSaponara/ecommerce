package com.TreDL.ecommerce.release;

import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.net.URI;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Shared fail-closed policy. Never includes configuration values in failures. */
public final class ReleaseDatabase {
    private ReleaseDatabase() {}
    public static final String PROFILE = "checkout-release";
    public static final String MYSQL_VERSION = "8.4.3";
    public static final Set<String> TABLES=Set.of("customers","products","cart","cart_products","orders","order_items");
    private static final Set<String> RUNTIME = Set.of("SELECT", "INSERT", "UPDATE", "DELETE");
    private static final Set<String> MIGRATION = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "INDEX", "REFERENCES");

    public static final class Target {
        public final String url, database, serverUuid, username, destination;
        private final String password;
        private Target(String url, String database, String uuid, String username, String password, String destination) {
            this.url=url; this.database=database; this.serverUuid=uuid; this.username=username; this.password=password; this.destination=destination;
        }
        public DataSource runtimeSource() { return source(this, username, password, "runtime"); }
        public DataSource migrationSource(Function<String,String> values) {
            String user=required(values,"CHECKOUT_V2_MIGRATION_USERNAME"), pass=required(values,"CHECKOUT_V2_MIGRATION_PASSWORD");
            require(user.matches("[A-Za-z][A-Za-z0-9_]{0,31}") && !user.equals(username) && !pass.equals(password), "Distinct migration credentials required");
            return source(this,user,pass,"migration");
        }
        public DataSource runtimePreflightSource(){return source(this,username,password,"empty");}
        public String configuredHost(){return URI.create(url.substring(5)).getHost();}
        public int configuredPort(){return URI.create(url.substring(5)).getPort();}
        @Override public String toString() { return "ReleaseDatabase.Target[protected]"; }
    }
    public static Target target(Function<String,String> values) {
        String url=required(values,"CHECKOUT_V2_DB_URL"), db=required(values,"CHECKOUT_V2_DB_NAME");
        String uuid=required(values,"CHECKOUT_V2_DB_SERVER_UUID"), user=required(values,"CHECKOUT_V2_DB_USERNAME"), pass=required(values,"CHECKOUT_V2_DB_PASSWORD");
        require(db.matches("checkoutv2[a-z0-9]{1,40}"),"A dedicated new Checkout V2 database name is required");
        require(uuid.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"),"Expected server UUID required");
        require(user.matches("[A-Za-z][A-Za-z0-9_]{0,31}"),"Invalid runtime username");
        URI uri;
        try { require(url.startsWith("jdbc:mysql://"),"Single MySQL destination required"); uri=URI.create(url.substring(5)); }
        catch(Exception failure) { throw new IllegalStateException("Invalid release database URL"); }
        require("mysql".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null && uri.getFragment()==null
                && uri.getPort()>0 && uri.getPort()<=65535 && ("/"+db).equals(uri.getRawPath()),"Database URL/identity mismatch");
        // An allowlist excludes credentials, reconnect/failover, sessionVariables,
        // schema creation, alternate sockets and query/URL injection.
        Set<String> keys=new HashSet<>(); String ssl=null;
        for(String pair:Objects.toString(uri.getRawQuery(),"").split("&")) {
            String[] parts=pair.split("=",-1);
            require(parts.length==2 && keys.add(parts[0]),"Invalid database URL options");
            switch(parts[0]) {
                case "sslMode" -> { ssl=parts[1]; require(Set.of("REQUIRED","VERIFY_IDENTITY").contains(ssl),"Encrypted MySQL transport required"); }
                case "connectTimeout", "socketTimeout" -> require(parts[1].matches("[1-9][0-9]{0,5}"),"Invalid connection timeout");
                case "serverTimezone" -> require("UTC".equals(parts[1]),"UTC timezone required");
                default -> throw new IllegalStateException("Database URL option forbidden");
            }
        }
        require(ssl!=null,"Explicit encrypted MySQL transport required");
        require("VERIFY_IDENTITY".equals(ssl) || Set.of("127.0.0.1","::1","[::1]").contains(uri.getHost()),"Remote MySQL requires TLS identity verification");
        return new Target(url,db,uuid,user,pass,"mysql://"+uri.getHost()+":"+uri.getPort()+"/"+db);
    }
    public static void hmac(String value) {
        byte[] bytes;
        try { require(value!=null && !value.isBlank(),"Release signing secret required"); bytes=Base64.getDecoder().decode(value); }
        catch(Exception failure) { throw new IllegalStateException("Release signing secret must be valid Base64 with at least 32 bytes"); }
        boolean valid=bytes.length>=32; Arrays.fill(bytes,(byte)0);
        require(valid,"Release signing secret must be valid Base64 with at least 32 bytes");
    }
    public static String required(Function<String,String> values,String name) {
        String value=values.apply(name); require(value!=null && !value.isBlank(),"Required protected setting missing: "+name); return value;
    }
    public static void require(boolean value,String message) { if(!value) throw new IllegalStateException(message); }
    public static void identity(Connection c,Target target) throws SQLException {
        require(target.url.split("\\?",2)[0].equals(c.getMetaData().getURL().split("\\?",2)[0]),"Unexpected JDBC destination");
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DATABASE(),@@server_uuid,VERSION()")) {
            require(r.next() && target.database.equals(r.getString(1)) && target.serverUuid.equals(r.getString(2))
                    && MYSQL_VERSION.equals(r.getString(3)),"Unexpected database/server identity or version");
        }
    }
    public static void requireV1(Connection c) throws SQLException {
        try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT version,script,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
            require(r.next() && "1".equals(r.getString(1)) && "V1__create_checkout_v2.sql".equals(r.getString(2)) && r.getBoolean(3)
                    && !r.next(),"Exactly one successful approved V1 required");
        }
    }
    public static String account(Connection c) throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT CURRENT_USER()")){require(r.next(),"Database account unavailable");return r.getString(1);}
    }
    public static void privileges(Connection c,String database,String user,String stage) throws SQLException {
        require(account(c).startsWith(user+"@"),"Unexpected authenticated database user");
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT CURRENT_ROLE(),@@mandatory_roles")) {
            require(r.next() && "NONE".equals(r.getString(1)) && Objects.toString(r.getString(2),"").isBlank(),"Active/mandatory roles forbidden");
        }
        Map<String,Set<String>> found=new HashMap<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW GRANTS")) {
            while(r.next()){
                String g=r.getString(1);require(!g.contains("WITH GRANT OPTION"),"Grant option forbidden");
                if(g.startsWith("GRANT USAGE ON *.* TO "))continue;
                var m=java.util.regex.Pattern.compile("^GRANT (.+) ON "+(char)96+database+(char)96+"\\.(\\*|"+(char)96+"([a-z_]+)"+(char)96+") TO .+$").matcher(g);
                require(m.matches(),"Global, foreign, column or activatable role grants forbidden");
                String table=m.group(2).equals("*")?"*":m.group(3);
                Set<String> allowed=stage.equals("migration") && table.equals("*")?MIGRATION
                    :stage.equals("runtime") && TABLES.contains(table)?RUNTIME
                    :stage.equals("runtime") && table.equals("flyway_schema_history")?Set.of("SELECT"):Set.of();
                for(String grant:m.group(1).split(", ")){require(allowed.contains(grant),"Excess database privilege");found.computeIfAbsent(table,k->new HashSet<>()).add(grant);}
            }
        }
        if(stage.equals("migration"))require(found.getOrDefault("*",Set.of()).equals(MIGRATION),"Migration privileges missing");
        if(stage.equals("runtime")){
            for(String table:TABLES)require(found.getOrDefault(table,Set.of()).equals(RUNTIME),"Runtime table privileges missing");
            require(found.getOrDefault("flyway_schema_history",Set.of()).equals(Set.of("SELECT")),"History must be read-only");
        }
    }
    private static DataSource source(Target target,String user,String password,String stage) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection c=null;
                try {
                    String jdbc=target.url;
                    if(stage.equals("empty")){int slash=jdbc.indexOf('/',"jdbc:mysql://".length());jdbc=jdbc.substring(0,slash+1)+jdbc.substring(jdbc.indexOf('?'));}
                    Properties auth=new Properties();auth.setProperty("user",user);auth.setProperty("password",password);auth.setProperty("connectTimeout","5000");auth.setProperty("socketTimeout","15000");
                    c=DriverManager.getConnection(jdbc,auth);
                    if(stage.equals("empty")){
                        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT DATABASE(),@@server_uuid,VERSION()")){require(r.next() && r.getString(1)==null && target.serverUuid.equals(r.getString(2)) && MYSQL_VERSION.equals(r.getString(3)),"Unexpected pre-grant server");}
                    }else identity(c,target);
                    privileges(c,target.database,user,stage);return c;
                }
                catch(Exception failure) { if(c!=null)try{c.close();}catch(SQLException ignored){} throw new SQLException("Release database identity/privileges/connection check failed"); }
            }
            @Override public Connection getConnection(String user,String password) throws SQLException { throw new SQLException("External credentials forbidden"); }
        };
    }
}
