package com.TreDL.ecommerce.release;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Windows-only append-only ledger. Never repairs, replaces or removes a record. */
public final class WindowsPlanStore {
    private static final ObjectMapper JSON=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private final Path root,helper;
    public WindowsPlanStore(Path root,Path helper){this.root=root.toAbsolutePath().normalize();this.helper=helper.toAbsolutePath().normalize();policy();}
    public static void initialize(Path root,Path helper){invoke(root,helper,"Init",null,"");}
    public record Policy(String storeId,String ownerSid,String nodeId){}
    public Policy policy(){try{JsonNode n=JSON.readTree(call("Validate",null,""));return new Policy(n.path("storeId").asText(),n.path("ownerSid").asText(),n.path("nodeId").asText());}catch(Exception e){throw new IllegalStateException("Private store validation failed");}}
    private String call(String action,String name,String input){return invoke(root,helper,action,name,input);}
    private static Process start(Path root,Path helper,String action,String name) throws Exception {
        ReleaseDatabase.require(Files.isRegularFile(helper) && !Files.isSymbolicLink(helper),"Native store helper unavailable");
        var args=new ArrayList<>(List.of("pwsh","-NoProfile","-NonInteractive","-File",helper.toString(),"-Action",action,"-Root",root.toString()));
        if(name!=null){args.add("-Name");args.add(name);}return new ProcessBuilder(args).redirectErrorStream(false).start();
    }
    private static String invoke(Path root,Path helper,String action,String name,String input){
        Process p=null;try{p=start(root,helper,action,name);p.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));p.getOutputStream().close();
            var stream=p.getInputStream();var output=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return stream.readNBytes(131073);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
            if(!p.waitFor(15,TimeUnit.SECONDS) || p.exitValue()!=0)throw new IllegalStateException();
            byte[] bytes=output.get(2,TimeUnit.SECONDS);ReleaseDatabase.require(bytes.length<=131072,"Oversized store response");return new String(bytes,StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException("Protected NTFS store operation failed",e);}finally{if(p!=null && p.isAlive())p.destroyForcibly();}
    }
    public final class LocalMutex implements AutoCloseable {
        private final Process p;
        private LocalMutex(String resource){
            Process started=null;
            try{started=start(root,helper,"Mutex",resource);var stream=started.getInputStream();
                var ready=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return new java.io.BufferedReader(new java.io.InputStreamReader(stream,StandardCharsets.UTF_8)).readLine();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
                if(!"LOCKED".equals(ready.get(15,TimeUnit.SECONDS)))throw new IllegalStateException();
                p=started;
            }catch(Exception e){if(started!=null)started.destroyForcibly();throw new IllegalStateException("Destination already active or local mutex unavailable");}
        }
        public void check(){ReleaseDatabase.require(p.isAlive(),"Local coordinator lost");}
        @Override public void close(){try{p.getOutputStream().write(10);p.getOutputStream().close();if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly();}catch(Exception e){p.destroyForcibly();}}
    }
    public LocalMutex mutex(String resource){return new LocalMutex(resource);}
    public static String resource(String uuid,String database){return MigrationPlan.sha256((uuid+"\n"+database).getBytes(StandardCharsets.UTF_8));}
    private static String file(String kind,String id){ReleaseDatabase.require(id.matches("[a-f0-9]{64}"),"Invalid record fingerprint");return kind+"-"+id+".json";}
    public boolean exists(String kind,String id){return "true".equals(call("Exists",file(kind,id),""));}
    public String read(String kind,String id){return call("Read",file(kind,id),"");}
    public void plan(MigrationPlan plan){var p=policy();ReleaseDatabase.require(plan.storeId().equals(p.storeId()) && plan.operatorSid().equals(p.ownerSid()) && plan.nodeId().equals(p.nodeId()),"Plan/store identity mismatch");call("Write",file("plan",plan.fingerprint()),new String(plan.canonical(),StandardCharsets.UTF_8));}
    public MigrationPlan load(String id){MigrationPlan p=MigrationPlan.read(read("plan",id),id);var policy=policy();ReleaseDatabase.require(p.storeId().equals(policy.storeId()) && p.operatorSid().equals(policy.ownerSid()) && p.nodeId().equals(policy.nodeId()),"Plan copied to different store/operator/node");return p;}
    public void record(String kind,String key,String planId){try{var p=policy();var record=new TreeMap<String,Object>();record.put("kind",kind);record.put("planId",planId);record.put("storeId",p.storeId());record.put("operatorSid",p.ownerSid());record.put("at",Instant.now().getEpochSecond());call("Write",file(kind,key),JSON.writeValueAsString(record));}catch(Exception e){throw new IllegalStateException("Durable append-only record failed");}}
    public void requireRecord(String kind,String key,String planId){try{String text=read(kind,key);JsonNode n=JSON.readTree(text);var p=policy();ReleaseDatabase.require(n.path("kind").asText().equals(kind) && n.path("planId").asText().equals(planId) && n.path("storeId").asText().equals(p.storeId()) && n.path("operatorSid").asText().equals(p.ownerSid()),"Invalid store record");}catch(Exception e){throw new IllegalStateException("Missing/corrupt protected record");}}
}
