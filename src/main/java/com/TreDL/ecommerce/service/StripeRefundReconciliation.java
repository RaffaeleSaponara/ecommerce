package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stripe.Stripe;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Durable refund work; no financial call is made while a database transaction is open. */
@Service
@ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
class StripeRefundReconciliation {
    @PersistenceContext private EntityManager em;
    private final StripeRefundOperations operations;
    private final StripeRefundGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json=new ObjectMapper();
    private final String keyHash;
    StripeRefundReconciliation(StripeRefundOperations operations,StripeRefundGateway gateway,JdbcTemplate jdbc,
            PlatformTransactionManager manager,@Value("${payments.stripe.secret-key:}") String key){
        this.operations=operations;this.gateway=gateway;this.jdbc=jdbc;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try{keyHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("Configurazione rimborsi non valida");}
    }
    @Scheduled(fixedDelayString="${payments.stripe.reconcile-delay-ms:60000}")
    void scheduled(){try{tick();}catch(Exception unavailable){/* Durable rows remain pending; no sensitive logs. */}}
    void tick(){
        StripeRefundOperations.outside();var now=operations.now();
        for(String id:jdbc.queryForList("SELECT id FROM refunds WHERE status IN ('REQUESTED','PENDING') AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at LIMIT 20",String.class,now,now)){
            String token=UUID.randomUUID().toString();
            if(jdbc.update("UPDATE refunds SET lease_token=?,lease_until=? WHERE id=? AND status IN ('REQUESTED','PENDING') AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)",token,now.plusMinutes(3),id,now,now)==0)continue;
            try{process(id,token);}catch(StripeRefundGateway.Failure failure){retry(id,token,failure.definitive?"REVIEW_REQUIRED":"PROVIDER_UNAVAILABLE");}
            catch(Exception failure){retry(id,token,"RETRY_REQUIRED");}
        }
    }
    private void process(String id,String token) throws Exception {
        var row=jdbc.queryForMap("SELECT r.*,a.id_order,a.verified_charge_id,a.provider_payment_id FROM refunds r JOIN payment_attempts a ON a.id=r.attempt_id WHERE r.id=?",id);
        if(!token.equals(row.get("lease_token")))return;
        var target=operations.target(((Number)row.get("id_order")).longValue());
        if(((java.math.BigDecimal)row.get("amount")).movePointRight(2).longValueExact()!=target.amount() ||
            !"EUR".equals(row.get("currency")) || !("3dl-refund-"+id).equals(row.get("idempotency_key"))){
            retry(id,token,"REFUND_SNAPSHOT_MISMATCH");return;
        }
        // Freeze every POST parameter and configuration before the first possible provider call.
        String frozen=text(row.get("request_json"));
        if(frozen==null){
            if(row.get("verified_charge_id")==null || row.get("provider_payment_id")==null){retry(id,token,"CAPTURE_NOT_VERIFIED");return;}
            var params=Map.<String,Object>of("charge",row.get("verified_charge_id"),"amount",target.amount(),
                "metadata",Map.of("refund_id",id,"order_id",Long.toString(target.order()),"attempt_id",target.attempt()));
            frozen=json.writeValueAsString(Map.of("params",params,"key",keyHash,"api",Stripe.API_VERSION));
            if(jdbc.update("UPDATE refunds SET request_json=?,first_sent_at=?,updated_at=? WHERE id=? AND lease_token=? AND request_json IS NULL",frozen,operations.now(),operations.now(),id,token)==0)return;
            row=jdbc.queryForMap("SELECT * FROM refunds WHERE id=?",id);
        }
        Map<String,Object> envelope=json.readValue(frozen,new TypeReference<>(){});
        if(!keyHash.equals(envelope.get("key")) || !Stripe.API_VERSION.equals(envelope.get("api"))){retry(id,token,"CONFIGURATION_CHANGED");return;}
        var params=(Map<String,Object>)envelope.get("params");
        if(!(params.get("metadata") instanceof Map<?,?> metadata) || !id.equals(metadata.get("refund_id")) ||
            !Long.toString(target.order()).equals(metadata.get("order_id")) || !target.attempt().equals(metadata.get("attempt_id"))){
            retry(id,token,"REFUND_SNAPSHOT_MISMATCH");return;
        }
        var capture=operations.verify(target);
        if(!Objects.equals(params.get("charge"),capture.charge()) || ((Number)params.get("amount")).longValue()!=capture.amount()){
            retry(id,token,"CAPTURE_MISMATCH");return;
        }
        String known=(String)row.get("provider_refund_id");
        if(known!=null){
            var remote=gateway.retrieve(known);
            if(!valid(remote,capture,id,target) || !known.equals(remote.id())){retry(id,token,"REFUND_MISMATCH");return;}
            apply(id,token,remote,target);return;
        }
        // Exhaustive provider listing precedes every possible creation, including retries.
        var existing=gateway.list(capture.charge());
        if(existing==null){retry(id,token,"REFUND_LIST_UNAVAILABLE");return;}
        if(!existing.isEmpty()){
            if(existing.size()!=1 || !valid(existing.get(0),capture,id,target)){retry(id,token,"EXISTING_REFUND_CONFLICT");return;}
            associate(id,token,existing.get(0).id());return;
        }
        var first=date(row.get("first_sent_at"));
        String issue=(String)row.get("issue");
        Object blocked=row.get("create_blocked");
        if(first==null || !operations.now().isBefore(first.plusHours(12)) || Boolean.TRUE.equals(blocked) || (blocked instanceof Number n && n.intValue()!=0) || "REVIEW_REQUIRED".equals(issue)){
            retry(id,token,"REVIEW_REQUIRED");return;
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE id=? AND lease_token=? AND lease_until>?",Integer.class,id,token,operations.now())!=1)return;
        var remote=gateway.create(params,(String)row.get("idempotency_key"));
        if(!valid(remote,capture,id,target)){retry(id,token,"REFUND_MISMATCH");return;}
        // Creation acceptance is not completion: a later GET must verify the final outcome.
        associate(id,token,remote.id());
    }
    private boolean valid(StripeRefundGateway.RemoteRefund r,StripeRefundGateway.Capture c,String id,StripeRefundOperations.Target t){
        return r!=null && r.id()!=null && r.id().startsWith("re_") && c.payment().equals(r.payment()) && c.charge().equals(r.charge()) &&
            r.amount()==c.amount() && "eur".equals(r.currency()) && r.status()!=null &&
            (r.metadata()==null || ((!r.metadata().containsKey("refund_id") || id.equals(r.metadata().get("refund_id"))) &&
            (!r.metadata().containsKey("order_id") || Long.toString(t.order()).equals(r.metadata().get("order_id"))) &&
            (!r.metadata().containsKey("attempt_id") || t.attempt().equals(r.metadata().get("attempt_id")))));
    }
    private void associate(String id,String token,String provider){
        jdbc.update("UPDATE refunds SET provider_refund_id=?,status='PENDING',issue=NULL,updated_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",
            provider,operations.now(),operations.now().plusMinutes(1),id,token);
    }
    private void apply(String id,String token,StripeRefundGateway.RemoteRefund remote,StripeRefundOperations.Target t){
        tx.executeWithoutResult(s->{
            var o=em.find(Orders.class,t.order(),LockModeType.PESSIMISTIC_WRITE);em.refresh(o,LockModeType.PESSIMISTIC_WRITE);
            var a=em.find(PaymentAttempt.class,t.attempt(),LockModeType.PESSIMISTIC_WRITE);em.refresh(a,LockModeType.PESSIMISTIC_WRITE);
            var r=jdbc.queryForMap("SELECT * FROM refunds WHERE id=? FOR UPDATE",id);
            if(!token.equals(r.get("lease_token")) || !remote.id().equals(r.get("provider_refund_id")))return;
            if(!"ANNULLATO".equals(o.getShipment()) || !Objects.equals(a.getPaymentId(),remote.payment()) || a.getAmount().movePointRight(2).longValueExact()!=remote.amount())throw new IllegalStateException("Inconsistent refund");
            String status,issue=null;
            switch(remote.status()){
                case "succeeded" -> status="SUCCEEDED";
                case "pending" -> status="PENDING";
                case "requires_action" -> {status="PENDING";issue="ACTION_REQUIRED";}
                case "failed","canceled" -> {status="FAILED";issue="REFUND_FAILED";}
                default -> {status="PENDING";issue="UNKNOWN_PROVIDER_STATUS";}
            }
            jdbc.update("UPDATE refunds SET status=?,provider_status=?,issue=?,updated_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",
                status,remote.status(),issue,operations.now(),"PENDING".equals(status)?operations.now().plusMinutes(5):null,id,token);
            if("SUCCEEDED".equals(status))jdbc.update("UPDATE payment_events SET resolved_at=? WHERE outcome='ANOMALY' AND resolved_at IS NULL AND session_ref=? AND (attempt_id IS NULL OR attempt_id=?) AND (attempt_hint IS NULL OR attempt_hint=?) AND (order_hint IS NULL OR order_hint=?) AND (attempt_id=? OR (attempt_hint=? AND order_hint=?))",
                operations.now(),a.getSessionId(),a.getId(),a.getId(),o.getId(),a.getId(),a.getId(),o.getId());
            // Deliberately no order transition and no stock.restore here.
        });
    }
    private void retry(String id,String token,String issue){
        jdbc.update("UPDATE refunds SET issue=?,create_blocked=CASE WHEN ? IN ('REVIEW_REQUIRED','EXISTING_REFUND_CONFLICT','REFUND_MISMATCH','CAPTURE_MISMATCH','REFUND_SNAPSHOT_MISMATCH') THEN TRUE ELSE create_blocked END,retries=retries+1,next_attempt_at=?,updated_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",
            issue,issue,operations.now().plusMinutes("REVIEW_REQUIRED".equals(issue)?60:5),operations.now(),id,token);
    }
    private LocalDateTime date(Object value){return value instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
    private String text(Object value) throws java.sql.SQLException {
        return value==null?null:value instanceof java.sql.Clob c?c.getSubString(1,Math.toIntExact(c.length())):value.toString();
    }
}
