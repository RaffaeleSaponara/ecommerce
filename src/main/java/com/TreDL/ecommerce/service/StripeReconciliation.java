package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

@Service @ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
class StripeReconciliation {
    @PersistenceContext private EntityManager em;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;
    private final StripeSessionGateway gateway;private final StripeCheckoutSessions sessions;private final StripePaymentDomain domain;
    StripeReconciliation(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,StripeSessionGateway gateway,StripeCheckoutSessions sessions,StripePaymentDomain domain){
        this.jdbc=jdbc;this.clock=clock;this.gateway=gateway;this.sessions=sessions;this.domain=domain;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Scheduled(fixedDelayString="${payments.stripe.reconcile-delay-ms:60000}")
    void scheduled(){
        try{tick();}catch(Exception unavailable){/* Durable work remains pending. No sensitive exception logging. */}
    }
    void tick(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("No ambient transaction");
        for(var row:jdbc.queryForList("SELECT id,id_order,provider_session_id FROM payment_attempts WHERE status IN ('CREATED','PENDING') AND next_reconcile_at<=? ORDER BY next_reconcile_at LIMIT 20",now())){
            String id="reconcile-"+row.get("id");
            try{jdbc.update("INSERT INTO payment_events(id,event_type,received_at,attempt_hint,order_hint,session_ref,next_attempt_at,retries) VALUES(?,?,?,?,?,?,?,0)",
                id,"reconcile",now(),row.get("id"),row.get("id_order"),row.get("provider_session_id"),now());}
            catch(DuplicateKeyException exists){
                jdbc.update("UPDATE payment_events SET processed_at=NULL,outcome=NULL,next_attempt_at=? WHERE id=? AND outcome='WAITING'",now(),id);
            }
            // Rotate due attempts so an old anomalous batch cannot starve later orders.
            jdbc.update("UPDATE payment_attempts SET next_reconcile_at=? WHERE id=? AND status IN ('CREATED','PENDING') AND next_reconcile_at<=?",
                now().plusMinutes(5),row.get("id"),now());
        }
        for(String id:jdbc.queryForList("SELECT id FROM payment_events WHERE processed_at IS NULL AND (next_attempt_at IS NULL OR next_attempt_at<=?) AND (lease_until IS NULL OR lease_until<=?) ORDER BY received_at LIMIT 20",String.class,now(),now())){
            String token=UUID.randomUUID().toString();
            if(jdbc.update("UPDATE payment_events SET lease_token=?,lease_until=? WHERE id=? AND processed_at IS NULL AND (next_attempt_at IS NULL OR next_attempt_at<=?) AND (lease_until IS NULL OR lease_until<=?)",token,now().plusMinutes(3),id,now(),now())==0)continue;
            try{process(id,token);}
            catch(Exception retry){
                Integer retries=jdbc.queryForObject("SELECT retries FROM payment_events WHERE id=?",Integer.class,id);
                long delay=Math.min(60,1L<<Math.min(retries==null?0:retries,6));
                jdbc.update("UPDATE payment_events SET retries=retries+1,outcome='RETRY',next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",
                    now().plusMinutes(delay),id,token);
            }
        }
    }
    private record Target(String attempt,String session,long order,boolean close,boolean due,long amount,String payment,boolean terminal) {}
    private Target target(Map<String,Object> event){
        String hint=(String)event.get("attempt_hint"),session=(String)event.get("session_ref");
        if(hint==null)hint=(String)event.get("attempt_id");
        var ids=em.createQuery("select a.id from PaymentAttempt a where a.id=:hint or a.sessionId=:session",String.class)
            .setParameter("hint",hint).setParameter("session",session).getResultList();
        if(ids.size()!=1)return null;
        PaymentAttempt a=em.find(PaymentAttempt.class,ids.get(0));
        if(event.get("order_hint")!=null && ((Number)event.get("order_hint")).longValue()!=a.getOrder().getId())return null;
        if(session!=null && a.getSessionId()!=null && !session.equals(a.getSessionId()))return null;
        if(a.getOrder().getPaymentFlow()!=PaymentFlow.STRIPE_TEST)return null;
        return new Target(a.getId(),session==null?a.getSessionId():session,a.getOrder().getId(),a.getCloseRequestedAt()!=null,!a.getExpiresAt().isAfter(now()),a.getAmount().movePointRight(2).longValueExact(),a.getPaymentId(),a.getStatus().terminal());
    }
    private void process(String id,String token){
        var event=jdbc.queryForMap("SELECT * FROM payment_events WHERE id=?",id);
        Target target=tx.execute(s->target(event));
        if(target==null){anomaly(id,token);return;}
        // Provider reads and expiration have no database transaction or held row locks.
        StripeSessionGateway.Session remote=target.session()==null?sessions.inspect(target.attempt()):gateway.retrieve(target.session());
        if(!matches(target,remote)){anomaly(id,token);return;}
        if(!target.terminal() && (target.close() || target.due()) && "open".equals(remote.status()) && "unpaid".equals(remote.paymentStatus())){
            remote=gateway.expire(remote.id());
            if(!matches(target,remote)){anomaly(id,token);return;}
        }
        boolean paid="complete".equals(remote.status()) && "paid".equals(remote.paymentStatus());
        boolean negative=Set.of("expired","complete").contains(remote.status()) && "unpaid".equals(remote.paymentStatus());
        // A closed Checkout Session is not enough if its PaymentIntent might still settle.
        String paymentState=negative && remote.paymentId()!=null?gateway.paymentState(remote.paymentId()):null;
        if(!target.terminal() && negative && Set.of("requires_payment_method","requires_confirmation","requires_action").contains(paymentState==null?"":paymentState))
            paymentState=gateway.cancelPayment(remote.paymentId());
        boolean safeClose=negative && (("expired".equals(remote.status()) && remote.paymentId()==null) || "canceled".equals(paymentState));
        final var verified=remote;final boolean close=safeClose;final boolean success=paid;
        tx.executeWithoutResult(s->{
            Orders order=em.find(Orders.class,target.order(),LockModeType.PESSIMISTIC_WRITE);em.refresh(order,LockModeType.PESSIMISTIC_WRITE);
            PaymentAttempt a=em.find(PaymentAttempt.class,target.attempt(),LockModeType.PESSIMISTIC_WRITE);em.refresh(a,LockModeType.PESSIMISTIC_WRITE);
            var receipt=jdbc.queryForMap("SELECT * FROM payment_events WHERE id=? FOR UPDATE",id);
            if(!token.equals(receipt.get("lease_token")) || receipt.get("processed_at")!=null)return;
            if(!matches(target,verified) || order.getPaymentFlow()!=PaymentFlow.STRIPE_TEST ||
                a.getAmount().compareTo(BigDecimal.valueOf(verified.amount(),2))!=0 || !a.getCurrency().equalsIgnoreCase(verified.currency()) ||
                order.getTotal().compareTo(a.getAmount())!=0 ||
                (a.getSessionId()!=null && !a.getSessionId().equals(verified.id())) ||
                (a.getPaymentId()!=null && verified.paymentId()!=null && !a.getPaymentId().equals(verified.paymentId()))){
                finish(id,token,"ANOMALY",a.getId());return;
            }
            if(a.getStatus().terminal()){
                if((success && a.getStatus()!=PaymentStatus.SUCCEEDED) || (close && a.getStatus()==PaymentStatus.SUCCEEDED)){
                    finish(id,token,"ANOMALY",a.getId());return;
                }
                finish(id,token,"IGNORED",a.getId());return;
            }
            a.bindVerifiedSession(verified.id(),verified.paymentId());
            if(success)domain.confirm(a.getId(),BigDecimal.valueOf(verified.amount(),2),verified.currency().toUpperCase(Locale.ROOT));
            else if(close)domain.closeUnpaid(a.getId(),"expired".equals(verified.status())?PaymentStatus.EXPIRED:PaymentStatus.FAILED);
            else{
                a.scheduleReconciliation(now().plusMinutes(5));
                jdbc.update("UPDATE payment_events SET outcome='WAITING',next_attempt_at=?,lease_token=NULL,lease_until=NULL,attempt_id=? WHERE id=? AND lease_token=?",
                    now().plusMinutes(5),a.getId(),id,token);return;
            }
            finish(id,token,"APPLIED",a.getId());
        });
    }
    private boolean matches(Target t,StripeSessionGateway.Session r){
        return r!=null && !r.live() && r.id()!=null && r.id().startsWith("cs_test_") &&
            (t.session()==null || t.session().equals(r.id())) && r.metadata()!=null &&
            (t.payment()==null || t.payment().equals(r.paymentId())) &&
            t.attempt().equals(r.metadata().get("attempt_id")) && Long.toString(t.order()).equals(r.metadata().get("order_id")) &&
            "eur".equals(r.currency()) && r.amount()==t.amount() && Set.of("open","complete","expired").contains(r.status()==null?"":r.status()) &&
            Set.of("paid","unpaid","no_payment_required").contains(r.paymentStatus()==null?"":r.paymentStatus());
    }
    private void finish(String id,String token,String outcome,String attempt){
        jdbc.update("UPDATE payment_events SET processed_at=?,outcome=?,attempt_id=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",
            now(),outcome,attempt,id,token);
    }
    private void anomaly(String id,String token){
        jdbc.update("UPDATE payment_events SET outcome='ANOMALY',lease_token=NULL,lease_until=NULL,next_attempt_at=? WHERE id=? AND lease_token=?",
            now().plusHours(1),id,token);
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}

@Configuration @EnableScheduling
@ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${payments.stripe.scheduler.enabled:true}")
class StripeReconciliationScheduling {}
