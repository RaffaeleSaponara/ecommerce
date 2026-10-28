package com.TreDL.ecommerce.release;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative fencing: prevents NEW SQL after lease loss, not completion of in-flight DDL. */
public final class MigrationLease implements AutoCloseable {
    private final Connection control;
    private final String name;
    private final String database;
    private final long session;
    private final Runnable localCheck;
    private final AtomicBoolean failed=new AtomicBoolean();
    private final Set<Connection> active=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService watcher=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"migration-lease");t.setDaemon(true);return t;});
    public MigrationLease(DataSource source,String database,Runnable localCheck) throws SQLException {
        this.localCheck=localCheck;this.database=database;control=source.getConnection();name="3dl-v1:"+database;
        try {
            try(Statement s=control.createStatement();ResultSet r=s.executeQuery("SELECT CONNECTION_ID()")){ReleaseDatabase.require(r.next(),"Missing session identity");session=r.getLong(1);}
            try(PreparedStatement s=control.prepareStatement("SELECT GET_LOCK(?,0)")){s.setString(1,name);try(ResultSet r=s.executeQuery()){ReleaseDatabase.require(r.next() && r.getInt(1)==1,"Another MySQL migrator is active");}}
            check();watcher.scheduleWithFixedDelay(()->{try{check();}catch(Exception ignored){}},100,100,TimeUnit.MILLISECONDS);
        }catch(Exception e){control.close();watcher.shutdownNow();throw new SQLException("Migration lease acquisition failed");}
    }
    public synchronized void check() throws SQLException {
        if(failed.get())throw new SQLException("Migration lease lost");
        try{localCheck.run();ReleaseDatabase.require(!control.isClosed(),"Control connection closed");
            try(PreparedStatement s=control.prepareStatement("SELECT CONNECTION_ID(),IS_USED_LOCK(?)")){s.setString(1,name);try(ResultSet r=s.executeQuery()){
                ReleaseDatabase.require(r.next() && r.getLong(1)==session && r.getLong(2)==session && !r.wasNull(),"Advisory owner changed");}}
        }catch(Exception e){failed.set(true);throw new SQLException("Migration exclusivity cannot be demonstrated; manual review required");}
    }
    public DataSource fenced(DataSource source){return new org.springframework.jdbc.datasource.AbstractDataSource(){
        @Override public Connection getConnection()throws SQLException{check();Connection raw=source.getConnection();active.add(raw);return wrap(raw);}
        @Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("External credentials forbidden");}
    };}
    private Connection wrap(Connection raw){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,m,args)->{
        String n=m.getName();if(n.equals("close")){active.remove(raw);return invoke(m,raw,args);}
        if(n.equals("unwrap"))throw new SQLException("Native connection unwrapping forbidden");
        if(n.equals("isWrapperFor"))return false;
        if(Set.of("setCatalog","setSchema").contains(n)){check();if(args==null || !database.equals(args[0]))throw new SQLException("Migration destination switching forbidden");}
        if(n.equals("getMetaData")){
            check();DatabaseMetaData metadata=raw.getMetaData();
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(p,method,a)->{
                if(method.getName().equals("getConnection"))return proxy;
                if(method.getName().equals("unwrap"))throw new SQLException("Native metadata unwrapping forbidden");
                check();Object result=invoke(method,metadata,a);return result instanceof ResultSet rows?fencedRows(rows,null):result;
            });
        }
        if(n.equals("commit") || n.startsWith("set") || n.equals("releaseSavepoint"))check();
        if(Set.of("createStatement","prepareStatement","prepareCall").contains(n)){
            check();Statement statement=(Statement)invoke(m,raw,args);
            Class<?> api=statement instanceof CallableStatement?CallableStatement.class:statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{api},(p,method,a)->{
                if(method.getName().equals("getConnection"))return proxy;
                if(method.getName().equals("unwrap"))throw new SQLException("Native statement unwrapping forbidden");
                if(method.getName().startsWith("execute") || method.getName().equals("addBatch"))check();
                Object result=invoke(method,statement,a);return result instanceof ResultSet rows?fencedRows(rows,(Statement)p):result;
            });
        }return invoke(m,raw,args);
    });}
    private ResultSet fencedRows(ResultSet rows,Statement statement){
        return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("getStatement")){if(statement==null)throw new SQLException("Native metadata statement access forbidden");return statement;}
            if(name.equals("unwrap"))throw new SQLException("Native result unwrapping forbidden");
            if(name.equals("isWrapperFor"))return false;
            if(name.equals("next") || name.startsWith("update") || name.equals("insertRow") || name.equals("deleteRow"))check();
            return invoke(method,rows,args);
        });
    }
    private static Object invoke(Method m,Object target,Object[] args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    public long sessionId(){return session;}
    @Override public void close(){watcher.shutdownNow();for(Connection c:active)try{c.close();}catch(SQLException ignored){};try{control.close();}catch(SQLException ignored){}}
}
