package com.TreDL.ecommerce.release;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;

/** Single executor: protected ledger + Windows mutex + MySQL lease + explicit approval. */
public final class MigrationWorkflow {
    public interface Runner {void migrate(DataSource source,byte[] v1);void validate(DataSource source,byte[] v1);}
    private final ReleaseDatabase.Target target;
    private final DataSource migration;
    private final WindowsPlanStore store;
    private final byte[] v1;
    private final Clock clock;
    private final Runner runner;
    private final String flywayVersion;
    public MigrationWorkflow(ReleaseDatabase.Target target,DataSource migration,WindowsPlanStore store,byte[] v1,Clock clock,String version,Runner runner){
        this.target=target;this.migration=migration;this.store=store;this.v1=v1.clone();this.clock=clock;this.runner=runner;this.flywayVersion=version;
        ReleaseDatabase.require("10.20.1".equals(version),"Qualified Flyway version required");
    }
    private String resource(){return WindowsPlanStore.resource(target.serverUuid,target.database);}
    public String preflight()throws Exception {
        String resource=resource();try(var local=store.mutex(resource);var lease=new MigrationLease(migration,target.database,local::check)){
            ReleaseDatabase.require(!store.exists("start",resource),"Destination has a prior attempt; manual review required");
            var seen=new FreshDatabaseMigration(target,migration).preflight();lease.check();var p=store.policy();
            MigrationPlan plan=new MigrationPlan(1,p.storeId(),p.ownerSid(),p.nodeId(),target.configuredHost(),seen.host(),seen.port(),target.serverUuid,target.database,
                seen.migrationAccount(),seen.runtimeAccount(),ReleaseDatabase.MYSQL_VERSION,flywayVersion,MigrationPlan.sha256(v1),"EMPTY_NO_VISIBLE_TABLES_OR_ROUTINES",UUID.randomUUID().toString(),clock.instant().plusSeconds(900).getEpochSecond());
            store.plan(plan);return plan.fingerprint();
        }
    }
    public void approve(String id){approve(store,id,clock);}
    public static void approve(WindowsPlanStore store,String id,Clock clock){MigrationPlan p=store.load(id);try(var local=store.mutex(WindowsPlanStore.resource(p.serverUuid(),p.database()))){
        ReleaseDatabase.require(clock.instant().getEpochSecond()<p.expiresAt(),"Plan expired");
        ReleaseDatabase.require(!store.exists("used",id) && !store.exists("start",WindowsPlanStore.resource(p.serverUuid(),p.database())),"Plan/resource already attempted");
        store.record("approval",id,id);
    }}
    private void unexpired(MigrationPlan p){ReleaseDatabase.require(clock.instant().getEpochSecond()<p.expiresAt(),"Plan expired");}
    private void targetMatches(MigrationPlan p){ReleaseDatabase.require(p.configuredHost().equals(target.configuredHost()) && p.port()==target.configuredPort() && p.serverUuid().equals(target.serverUuid)
        && p.initialSchema().equals("EMPTY_NO_VISIBLE_TABLES_OR_ROUTINES") && p.database().equals(target.database) && p.mysqlVersion().equals(ReleaseDatabase.MYSQL_VERSION) && p.flywayVersion().equals(flywayVersion) && p.v1Sha256().equals(MigrationPlan.sha256(v1)),"Approved target/version/V1 changed");}
    public void migrate(String id)throws Exception {
        MigrationPlan p=store.load(id);String resource=WindowsPlanStore.resource(p.serverUuid(),p.database());
        try(var local=store.mutex(resource)){
            unexpired(p);store.requireRecord("approval",id,id);
            ReleaseDatabase.require(!store.exists("used",id) && !store.exists("start",resource),"Plan/resource consumed or uncertain");
            // CREATE_NEW + WriteThrough + Flush(true), before any database write.
            store.record("start",resource,id);store.record("used",id,id);
            try {
                targetMatches(p);
                try(var lease=new MigrationLease(migration,target.database,local::check)){
                    var seen=new FreshDatabaseMigration(target,migration).preflight();
                    ReleaseDatabase.require(p.effectiveHost().equals(seen.host()) && p.port()==seen.port() && p.migrationAccount().equals(seen.migrationAccount()) && p.runtimeAccount().equals(seen.runtimeAccount()),"Authenticated endpoint/account changed");
                    lease.check();runner.migrate(lease.fenced(migration),v1.clone());lease.check();
                    runner.validate(lease.fenced(migration),v1.clone());lease.check();
                }
                store.record("success",resource,id);
            }catch(Throwable failure){try{store.record("failure",resource,id);}catch(Exception ignored){};throw new IllegalStateException("Migration failed/uncertain; plan consumed, destination blocked; category="+failure.getClass().getSimpleName()+"; manual review required");}
        }
    }
    public void verify(String id)throws Exception {
        MigrationPlan p=store.load(id);String resource=WindowsPlanStore.resource(p.serverUuid(),p.database());
        try(var local=store.mutex(resource);var lease=new MigrationLease(migration,target.database,local::check)){
            store.requireRecord("approval",id,id);store.requireRecord("used",id,id);store.requireRecord("success",resource,id);
            ReleaseDatabase.require(!store.exists("failure",resource),"Uncertain destination");targetMatches(p);
            new FreshDatabaseMigration(target,migration).verify();runner.validate(lease.fenced(migration),v1.clone());lease.check();
            if(!store.exists("verified",resource))store.record("verified",resource,id);
        }
    }
}
