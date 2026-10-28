package com.TreDL.ecommerce.release;
import javax.sql.DataSource;
import java.sql.*;
import java.net.Socket;

/** Schema and authenticated endpoint observations; no unauthenticated migration entry point. */
public final class FreshDatabaseMigration {
    private final ReleaseDatabase.Target target;
    private final DataSource migration;
    public FreshDatabaseMigration(ReleaseDatabase.Target target,DataSource migration){this.target=target;this.migration=migration;}
    public record Observation(String host,int port,String migrationAccount,String runtimeAccount){}
    public Observation preflight() throws SQLException {
        try(Connection m=migration.getConnection();Connection r=target.runtimePreflightSource().getConnection()) {
            empty(m);
            try {
                var session=(com.mysql.cj.NativeSession)m.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getSession();
                Socket socket=session.getProtocol().getSocketConnection().getMysqlSocket();
                return new Observation(socket.getInetAddress().getHostAddress(),socket.getPort(),ReleaseDatabase.account(m),ReleaseDatabase.account(r));
            }catch(java.io.IOException e){throw new SQLException("Effective endpoint unavailable");}
        }
    }
    private void empty(Connection c)throws SQLException {
        ReleaseDatabase.identity(c,target);
        for(String sql:new String[]{"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()","SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE()"})
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){ReleaseDatabase.require(r.next() && r.getLong(1)==0,"New empty schema required");}
    }
    public void verify()throws SQLException {
        try(Connection m=migration.getConnection();Connection r=target.runtimeSource().getConnection()){ReleaseDatabase.identity(m,target);ReleaseDatabase.requireV1(r);}
    }
}
