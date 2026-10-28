package com.TreDL.ecommerce.release;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Canonical, credential-free approval artifact; authentication is the protected operator channel. */
public record MigrationPlan(int protocol,String storeId,String operatorSid,String nodeId,String configuredHost,
    String effectiveHost,int port,String serverUuid,String database,String migrationAccount,String runtimeAccount,
    String mysqlVersion,String flywayVersion,String v1Sha256,String initialSchema,String nonce,long expiresAt) {
    private static final ObjectMapper JSON=new ObjectMapper();
    public byte[] canonical(){try{return JSON.writeValueAsBytes(this);}catch(Exception e){throw new IllegalStateException("Cannot encode migration plan");}}
    public String fingerprint(){return sha256(canonical());}
    public static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable");}}
    public static MigrationPlan read(String text,String expected){
        try{ReleaseDatabase.require(expected.matches("[a-f0-9]{64}"),"Invalid plan fingerprint");
            MigrationPlan p=JSON.readValue(text,MigrationPlan.class);
            ReleaseDatabase.require(p.protocol==1 && p.fingerprint().equals(expected) && Arrays.equals(text.getBytes(StandardCharsets.UTF_8),p.canonical()),"Modified/noncanonical migration plan");return p;
        }catch(Exception e){throw new IllegalStateException("Migration plan integrity check failed");}
    }
}
