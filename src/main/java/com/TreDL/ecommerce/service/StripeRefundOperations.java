package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Admin intent only. External verification precedes the short cancellation transaction. */
@Service
@ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
public class StripeRefundOperations {
    @PersistenceContext private EntityManager em;
    private final TransactionTemplate tx;
    final JdbcTemplate jdbc;
    private final StripeSessionGateway sessions;
    final StripeRefundGateway gateway;
    private final OrderStockService stock;
    private final OrderMailEvents mail;
    private final Clock clock;
    StripeRefundOperations(PlatformTransactionManager manager,JdbcTemplate jdbc,StripeSessionGateway sessions,
            StripeRefundGateway gateway,OrderStockService stock,OrderMailEvents mail,Clock clock){
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.jdbc=jdbc;this.sessions=sessions;this.gateway=gateway;this.stock=stock;this.mail=mail;this.clock=clock;
    }
    record Target(long order,String attempt,String session,String payment,long amount,String status,String orderStatus) {}
    public record View(long orderId,String paymentStatus,String refundStatus,String providerStatus,String issue,
                       BigDecimal amount,boolean canCancel,boolean canRefundLate,int anomalies) {}
    public record Anomaly(String eventId,Long orderId,String outcome) {}
    public record RefundSummary(long orderId,String status,String providerStatus,String issue,BigDecimal amount) {}
    LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("No external call in transaction");}
    Target target(long orderId){
        return tx.execute(s->{
            var list=em.createQuery("select a from PaymentAttempt a join fetch a.order o where o.id=:id",PaymentAttempt.class).setParameter("id",orderId).getResultList();
            if(list.isEmpty() || list.get(0).getOrder().getPaymentFlow()!=PaymentFlow.STRIPE_TEST)throw conflict("Ordine Stripe non disponibile");
            var a=list.get(0);var o=a.getOrder();String session=a.getSessionId();
            if(session==null){
                var refs=jdbc.queryForList("SELECT DISTINCT session_ref FROM payment_events WHERE session_ref IS NOT NULL AND (attempt_id=? OR (attempt_hint=? AND order_hint=?))",String.class,a.getId(),a.getId(),orderId);
                if(refs.size()==1)session=refs.get(0);
            }
            return new Target(orderId,a.getId(),session,a.getPaymentId(),o.getTotal().movePointRight(2).longValueExact(),a.getStatus().name(),o.getShipment());
        });
    }
    StripeRefundGateway.Capture verify(Target t){
        outside();if(t.session()==null)throw conflict("Incasso non associato: necessaria verifica");
        var r=sessions.retrieve(t.session());
        if(r==null || r.live() || !t.session().equals(r.id()) || !r.id().startsWith("cs_test_") ||
            !"complete".equals(r.status()) || !"paid".equals(r.paymentStatus()) || r.paymentId()==null ||
            (t.payment()!=null && !t.payment().equals(r.paymentId())) || r.amount()!=t.amount() || !"eur".equals(r.currency()) ||
            r.metadata()==null || !t.attempt().equals(r.metadata().get("attempt_id")) || !Long.toString(t.order()).equals(r.metadata().get("order_id")))
            throw conflict("Incasso non verificato o dati incoerenti");
        var c=gateway.capture(r.paymentId());
        if(c==null || c.live() || !c.succeeded() || c.amount()<=0 || c.amount()!=t.amount() || !"eur".equals(c.currency()) ||
            !r.paymentId().equals(c.payment()) || c.charge()==null)throw conflict("Incasso non verificato o dati incoerenti");
        return c;
    }
    public View request(long orderId,boolean cancel,boolean confirmed){
        outside();if(!confirmed)throw new ResponseStatusException(BAD_REQUEST,"Conferma esplicita obbligatoria");
        if(!jdbc.queryForList("SELECT id FROM refunds WHERE attempt_id IN (SELECT id FROM payment_attempts WHERE id_order=?)",orderId).isEmpty())return view(orderId);
        Target t=target(orderId);final StripeRefundGateway.Capture capture;
        try{capture=verify(t);}catch(ResponseStatusException e){throw e;}catch(RuntimeException unavailable){throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Verifica Stripe non disponibile: nessun annullamento eseguito");}
        tx.executeWithoutResult(s->{
            Orders o=em.find(Orders.class,orderId,LockModeType.PESSIMISTIC_WRITE);em.refresh(o,LockModeType.PESSIMISTIC_WRITE);
            PaymentAttempt a=em.find(PaymentAttempt.class,t.attempt(),LockModeType.PESSIMISTIC_WRITE);em.refresh(a,LockModeType.PESSIMISTIC_WRITE);
            if(!jdbc.queryForList("SELECT id FROM refunds WHERE attempt_id=? FOR UPDATE",a.getId()).isEmpty())return;
            if(o.getPaymentFlow()!=PaymentFlow.STRIPE_TEST || o.getTotal().compareTo(a.getAmount())!=0 ||
                a.getAmount().movePointRight(2).longValueExact()!=capture.amount() || !"EUR".equals(o.getCurrency()) || !"EUR".equals(a.getCurrency()))throw conflict("Snapshot non coerenti");
            if(cancel){
                if(a.getStatus()!=PaymentStatus.SUCCEEDED || !Set.of("CONFERMATO","IN_PREPARAZIONE").contains(o.getShipment()))throw conflict("Annullamento e rimborso non consentiti per questo stato");
                stock.restore(o);o.recordStatusChange(o.getShipment(),"ANNULLATO");o.setShipment("ANNULLATO");
            }else if(!"ANNULLATO".equals(o.getShipment()))throw conflict("Il rimborso finanziario richiede un ordine già annullato");
            a.recordVerifiedCapture(t.session(),capture.payment(),capture.charge(),now());
            em.flush();
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO refunds(id,attempt_id,status,amount,currency,idempotency_key,created_at,updated_at,next_attempt_at,retries,create_blocked) VALUES(?,?,'REQUESTED',?,'EUR',?,?,?,?,0,FALSE)",
                id,a.getId(),o.getTotal(),"3dl-refund-"+id,now(),now(),now());
            if(cancel)mail.transitioned(o);
        });
        return view(orderId);
    }
    public View view(long orderId){
        return tx.execute(s->{
            Orders o=em.find(Orders.class,orderId);if(o==null)throw new ResponseStatusException(NOT_FOUND,"Ordine non trovato");
            var ps=em.createQuery("select a from PaymentAttempt a where a.order.id=:id",PaymentAttempt.class).setParameter("id",orderId).getResultList();
            if(ps.isEmpty())return new View(orderId,"LEGACY",null,null,null,o.getTotal(),false,false,0);
            var a=ps.get(0);var refunds=jdbc.queryForList("SELECT status,provider_status,issue FROM refunds WHERE attempt_id=?",a.getId());
            var r=refunds.isEmpty()?null:refunds.get(0);
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE outcome='ANOMALY' AND resolved_at IS NULL AND (attempt_id=? OR order_hint=?)",Integer.class,a.getId(),orderId);
            return new View(orderId,a.getStatus().name(),r==null?null:(String)r.get("status"),r==null?null:(String)r.get("provider_status"),r==null?null:(String)r.get("issue"),o.getTotal(),
                r==null && a.getStatus()==PaymentStatus.SUCCEEDED && Set.of("CONFERMATO","IN_PREPARAZIONE").contains(o.getShipment()),r==null && "ANNULLATO".equals(o.getShipment()),count);
        });
    }
    public List<Anomaly> anomalies(){
        return jdbc.query("SELECT id,order_hint,outcome FROM payment_events WHERE outcome='ANOMALY' AND resolved_at IS NULL ORDER BY received_at LIMIT 100",
            (r,n)->new Anomaly(r.getString(1),r.getObject(2)==null?null:r.getLong(2),r.getString(3)));
    }
    public List<RefundSummary> refunds(){
        return jdbc.query("SELECT a.id_order,r.status,r.provider_status,r.issue,r.amount FROM refunds r JOIN payment_attempts a ON a.id=r.attempt_id ORDER BY CASE WHEN r.status='FAILED' THEN 0 WHEN r.status='SUCCEEDED' THEN 2 ELSE 1 END,r.updated_at DESC LIMIT 100",
            (r,n)->new RefundSummary(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getBigDecimal(5)));
    }
    static ResponseStatusException conflict(String message){return new ResponseStatusException(CONFLICT,message);}
}
