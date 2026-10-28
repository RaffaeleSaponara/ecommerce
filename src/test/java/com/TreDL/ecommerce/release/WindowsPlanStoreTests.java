package com.TreDL.ecommerce.release;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class WindowsPlanStoreTests {
    static final Path HELPER=Path.of("tools/checkout-release/NativeStore.ps1").toAbsolutePath();
    @TempDir Path directory;
    WindowsPlanStore store;
    Path root;
    @BeforeEach void init(){root=directory.resolve("store");WindowsPlanStore.initialize(root,HELPER);store=new WindowsPlanStore(root,HELPER);}
    MigrationPlan plan(){var p=store.policy();return new MigrationPlan(1,p.storeId(),p.ownerSid(),p.nodeId(),"127.0.0.1","127.0.0.1",3307,"00000000-0000-0000-0000-000000000001","checkoutv2audit","migrator@localhost","runtime@localhost","8.4.3","10.20.1","a".repeat(64),"EMPTY_NO_VISIBLE_TABLES_OR_ROUTINES",UUID.randomUUID().toString(),Instant.now().plusSeconds(900).getEpochSecond());}
    @Test void canonicalPlanRejectsTamperingAndFingerprintIsNotApproval(){
        var p=plan();store.plan(p);assertThat(store.load(p.fingerprint())).isEqualTo(p);
        assertThatThrownBy(()->store.requireRecord("approval",p.fingerprint(),p.fingerprint())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->MigrationPlan.read(new String(p.canonical(),StandardCharsets.UTF_8).replace("3307","3308"),p.fingerprint())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->MigrationPlan.read(new String(p.canonical(),StandardCharsets.UTF_8)+" ",p.fingerprint())).isInstanceOf(IllegalStateException.class);
    }
    @Test void tamperedPlanOnDiskIsRejected()throws Exception{
        var p=plan();store.plan(p);Files.writeString(root.resolve("plan-"+p.fingerprint()+".json"),"{}");
        assertThatThrownBy(()->store.load(p.fingerprint())).isInstanceOf(IllegalStateException.class);
    }
    @Test void weakDaclIsRejected()throws Exception{
        String script="param([string]$Root)\n$d=[IO.DirectoryInfo]::new($Root);$a=[IO.FileSystemAclExtensions]::GetAccessControl($d,[Security.AccessControl.AccessControlSections]::Access);$a.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-1-0'),'Write','Allow'));[IO.FileSystemAclExtensions]::SetAccessControl($d,$a)";
        Path weakening=directory.resolve("weaken-fixture-acl.ps1");Files.writeString(weakening,script);Process p=new ProcessBuilder("pwsh","-NoProfile","-NonInteractive","-File",weakening.toString(),"-Root",root.toString()).start();assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
        assertThatThrownBy(store::policy).isInstanceOf(IllegalStateException.class);
    }
    @Test void copiedPlanCannotAuthenticateAgainstAnotherStore(){
        var p=plan();Path other=directory.resolve("other");WindowsPlanStore.initialize(other,HELPER);var second=new WindowsPlanStore(other,HELPER);
        assertThatThrownBy(()->second.plan(p)).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentCreateNewHasExactlyOneWinnerAndCannotBeOverwritten()throws Exception{
        String id="b".repeat(64);var executor=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        Callable<Boolean> write=()->{go.await();try{store.record("used",id,id);return true;}catch(IllegalStateException e){return false;}};
        try{var a=executor.submit(write);var b=executor.submit(write);go.countDown();assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            store.requireRecord("used",id,id);assertThatThrownBy(()->store.record("used",id,id)).isInstanceOf(IllegalStateException.class);
        }finally{executor.shutdownNow();}
    }
    @Test void corruptConsumedRecordStillBlocksAndCannotBeRepaired()throws Exception{
        String id="c".repeat(64);store.record("used",id,id);Files.writeString(root.resolve("used-"+id+".json"),"{");
        assertThat(store.exists("used",id)).isTrue();assertThatThrownBy(()->store.requireRecord("used",id,id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->store.record("used",id,id)).isInstanceOf(IllegalStateException.class);
    }
    @Test void destinationMutexIsSharedAcrossDifferentStorePaths(){
        Path other=directory.resolve("other");WindowsPlanStore.initialize(other,HELPER);var second=new WindowsPlanStore(other,HELPER);String resource="d".repeat(64);
        try(var held=store.mutex(resource)){held.check();assertThatThrownBy(()->second.mutex(resource)).isInstanceOf(IllegalStateException.class);}
        try(var next=second.mutex(resource)){next.check();}
    }
}
