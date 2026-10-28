package com.TreDL.ecommerce.release;
import java.nio.file.*;
import java.time.Clock;
import javax.sql.DataSource;
import java.sql.*;
/** Child used only by the owned temporary-MySQL interruption test. */
public final class InterruptedMigrationWorker {
    public static void main(String[] args)throws Exception{
        var target=ReleaseDatabase.target(System::getenv);
        var store=new WindowsPlanStore(Path.of(args[0]),Path.of(args[1]));
        var runner=new MigrationWorkflow.Runner(){
            public void migrate(DataSource source,byte[] v1){
                try(Connection c=source.getConnection();Statement s=c.createStatement()){
                    s.execute("CREATE TABLE interrupted_partial(id INT)");
                    System.out.println("PARTIAL_READY");System.out.flush();
                    Thread.sleep(120000);
                }catch(Exception e){throw new IllegalStateException(e);}
            }
            public void validate(DataSource source,byte[] v1){}
        };
        new MigrationWorkflow(target,target.migrationSource(System::getenv),store,Files.readAllBytes(Path.of(args[3])),Clock.systemUTC(),"10.20.1",runner).migrate(args[2]);
    }
}
