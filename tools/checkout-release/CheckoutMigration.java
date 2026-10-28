package com.TreDL.ecommerce.tools;
import com.TreDL.ecommerce.release.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** One-shot Windows single-executor CLI. Approval never comes from a hash alone. */
public final class CheckoutMigration {
    public static MigrationWorkflow.Runner runner(){return new MigrationWorkflow.Runner(){
        public void migrate(DataSource source,byte[] bytes){flyway(source,bytes).migrate();}
        public void validate(DataSource source,byte[] bytes){flyway(source,bytes).validate();}
    };}
    private static Flyway flyway(DataSource source,byte[] snapshot){
        byte[] approved=snapshot.clone();
        LoadableResource resource=new LoadableResource(){
            public java.io.Reader read(){return new java.io.StringReader(new String(approved,StandardCharsets.UTF_8));}
            public String getFilename(){return "V1__create_checkout_v2.sql";}
            public String getAbsolutePath(){return getFilename();}
            public String getAbsolutePathOnDisk(){return null;}
            public String getRelativePath(){return getFilename();}
        };
        ResourceProvider provider=new ResourceProvider(){
            public LoadableResource getResource(String name){return name.equals(resource.getFilename())?resource:null;}
            public Collection<LoadableResource> getResources(String prefix,String[] suffixes){return resource.getFilename().startsWith(prefix) && Arrays.stream(suffixes).anyMatch(resource.getFilename()::endsWith)?List.of(resource):List.of();}
        };
        return Flyway.configure().dataSource(source).resourceProvider(provider).locations("classpath:approved-snapshot")
            .cleanDisabled(true).baselineOnMigrate(false).createSchemas(false).target("1").outOfOrder(false).validateMigrationNaming(true).load();
    }
    public static void main(String[] args){
        java.io.PrintStream report=System.out,errors=System.err;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        String stage="configuration";
        try{
            ReleaseDatabase.require(args.length==4 || args.length==5,"Expected ACTION V1 STORE HELPER [APPROVED_FINGERPRINT]");
            String action=args[0];stage=action;ReleaseDatabase.require(Set.of("InitStore","Preflight","Approve","Migrate","Verify").contains(action),"Unsupported action");
            Path root=Path.of(args[2]),helper=Path.of(args[3]);
            if(action.equals("InitStore")){WindowsPlanStore.initialize(root,helper);report.println("STORE INITIALIZED; Windows owner-only NTFS policy");return;}
            WindowsPlanStore store=new WindowsPlanStore(root,helper);
            if(action.equals("Approve")){ReleaseDatabase.require(args.length==5,"Explicit operator fingerprint required");MigrationWorkflow.approve(store,args[4],Clock.systemUTC());report.println("APPROVED "+args[4]);return;}
            ReleaseDatabase.require(CheckoutMigration.class.getClassLoader().getResource("application-test.properties")==null,"Project test resources forbidden");
            Path script=Path.of(args[1]).toRealPath();ReleaseDatabase.require(script.getFileName().toString().equals("V1__create_checkout_v2.sql") && !Files.isSymbolicLink(Path.of(args[1])),"Approved V1 path required");
            byte[] snapshot=Files.readAllBytes(script);
            String version=org.flywaydb.core.internal.license.VersionPrinter.getVersion();
            var target=ReleaseDatabase.target(System::getenv);
            var flow=new MigrationWorkflow(target,target.migrationSource(System::getenv),store,snapshot,Clock.systemUTC(),version,runner());
            if(action.equals("Preflight")){report.println("PLAN "+flow.preflight()+"; pending explicit trusted operator approval; expires in 900 seconds");}
            else{ReleaseDatabase.require(args.length==5,"Approved fingerprint required");if(action.equals("Migrate")){flow.migrate(args[4]);report.println("MIGRATED; DBA table grants and Verify required");}else{flow.verify(args[4]);report.println("VERIFIED; runtime table grants and read-only history confirmed");}}
        }catch(Throwable failure){errors.println("Release command failed closed; phase="+stage+"; category="+failure.getClass().getSimpleName()+"; inspect protected ledger; no automatic retry or fallback");System.exit(1);}
    }
}
