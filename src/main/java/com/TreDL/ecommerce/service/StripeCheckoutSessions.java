package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.util.*;

/** No HTTP mapping. Future trusted application orchestration only. */
@Service
@ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
class StripeCheckoutSessions {
    @PersistenceContext private EntityManager em;
    private final TransactionTemplate tx;
    private final StripeSessionGateway gateway;
    private final Clock clock;
    private final String base,keyHash;
    private final ObjectMapper json=new ObjectMapper();
    record Prepared(String id,String session,Map<String,Object> params,long amount,String fingerprint) {}
    StripeCheckoutSessions(PlatformTransactionManager manager,StripeSessionGateway gateway,Clock clock,
            @Value("${app.mail.public-url:http://127.0.0.1:9085}") String base,
            @Value("${payments.stripe.secret-key:}") String key){
        this.tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.gateway=gateway;this.clock=clock;this.base=base.replaceAll("/+$","");
        URI uri=URI.create(this.base);
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null ||
            !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1").contains(uri.getHost()))))
            throw new IllegalArgumentException("URL applicazione non sicuro");
        try{keyHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException();}
    }
    StripeSessionGateway.Session obtain(String attemptId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("External call requires no ambient transaction");
        Prepared p=tx.execute(s->prepare(attemptId));
        StripeSessionGateway.Session remote=null;
        for(int i=0;i<3;i++){
            try{remote=p.session()==null?gateway.create(p.params(),"3dl-session-"+p.id()):gateway.retrieve(p.session());break;}
            catch(SessionFailure error){if(!error.retryableConflict || i==2)throw error;}
        }
        validate(p,remote);
        final var result=remote;
        tx.executeWithoutResult(s->{
            PaymentAttempt a=locked(attemptId);eligible(a);
            a.associateSession(result.id(),result.paymentId(),LocalDateTime.ofInstant(Instant.ofEpochSecond(result.expiresAt()),ZoneOffset.UTC),now());
            em.flush();
        });
        if(!"open".equals(remote.status()) || !"unpaid".equals(remote.paymentStatus()))
            return new StripeSessionGateway.Session(remote.id(),remote.paymentId(),remote.amount(),remote.currency(),
                remote.live(),remote.status(),remote.paymentStatus(),null,remote.expiresAt(),remote.metadata());
        return remote; // status and payment_status are independent; neither confirms an order.
    }
    StripeSessionGateway.Session inspect(String id){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("External call requires no ambient transaction");
        Prepared p=tx.execute(s->prepare(id,true));
        var remote=p.session()==null?gateway.create(p.params(),"3dl-session-"+p.id()):gateway.retrieve(p.session());
        validate(p,remote);return remote;
    }
    private Prepared prepare(String id){return prepare(id,false);}
    private Prepared prepare(String id,boolean inspection){
        PaymentAttempt a=locked(id);
        if(!inspection)eligible(a);
        else if(a.getOrder().getPaymentFlow()!=PaymentFlow.STRIPE_TEST || a.getSessionRequest()==null)throw new SessionFailure(false);
        if(a.getSessionRequest()==null){
            if(!a.getExpiresAt().isAfter(now()))throw new SessionFailure(false);
            Orders o=a.getOrder();long amount=o.getTotal().movePointRight(2).longValueExact();
            if(amount<=0)throw new IllegalArgumentException("Importo non supportato per Checkout Stripe");
            var lines=new ArrayList<Map<String,Object>>();
            o.getItems().forEach(item->lines.add(line(item.getProductName(),item.getUnitPrice().movePointRight(2).longValueExact(),item.getQuantity())));
            if(o.getShippingCost().signum()>0)lines.add(line("Spedizione",o.getShippingCost().movePointRight(2).longValueExact(),1));
            long sum=0;for(var l:lines){var price=(Map<?,?>)l.get("price_data");sum=Math.addExact(sum,Math.multiplyExact(((Number)price.get("unit_amount")).longValue(),((Number)l.get("quantity")).longValue()));}
            if(sum!=amount || !"EUR".equals(o.getCurrency()) || a.getAmount().compareTo(o.getTotal())!=0)throw new IllegalStateException("Snapshot incoerenti");
            Map<String,Object> params=Map.of("mode","payment","payment_method_types",List.of("card"),
                "line_items",lines,"metadata",Map.of("order_id",o.getId().toString(),"attempt_id",id),
                "success_url",base+"/user/orders/"+o.getId()+"/payment/success","cancel_url",base+"/user/orders/"+o.getId()+"/payment/cancel",
                "expires_at",clock.instant().plusSeconds(1860).getEpochSecond());
            try{a.freezeSession(json.writeValueAsString(Map.of("params",params,"key",keyHash,"api",Stripe.API_VERSION)),now());}
            catch(Exception e){throw new IllegalStateException("Impossibile congelare la richiesta");}
        }
        try{
            Map<String,Object> envelope=json.readValue(a.getSessionRequest(),new TypeReference<>(){});
            if(!keyHash.equals(envelope.get("key")) || !Stripe.API_VERSION.equals(envelope.get("api")))throw new SessionFailure(false);
            if(a.getSessionId()==null && !now().isBefore(a.getSessionStartedAt().plusHours(12)))throw new SessionFailure(false);
            return new Prepared(id,a.getSessionId(),(Map<String,Object>)envelope.get("params"),a.getAmount().movePointRight(2).longValueExact(),keyHash);
        }catch(SessionFailure e){throw e;}catch(Exception e){throw new IllegalStateException("Richiesta persistita non valida");}
    }
    private Map<String,Object> line(String name,long cents,int quantity){
        return Map.of("price_data",Map.of("currency","eur","unit_amount",cents,"product_data",Map.of("name",name)),"quantity",quantity);
    }
    private void validate(Prepared p,StripeSessionGateway.Session s){
        if(s==null || s.live() || s.id()==null || s.status()==null || s.paymentStatus()==null ||
            !s.id().startsWith("cs_test_") || s.amount()!=p.amount() ||
            !"eur".equals(s.currency()) || s.metadata()==null || !p.id().equals(s.metadata().get("attempt_id")) ||
            !Objects.equals(((Map<?,?>)p.params().get("metadata")).get("order_id"),s.metadata().get("order_id")) ||
            !Set.of("open","complete","expired").contains(s.status()) ||
            !Set.of("unpaid","paid","no_payment_required").contains(s.paymentStatus()) ||
            (p.session()!=null && !p.session().equals(s.id())))throw new SessionFailure(false);
        if("open".equals(s.status()) && "unpaid".equals(s.paymentStatus())){
            try {
                URI url=URI.create(s.url());
                if(!"https".equals(url.getScheme()) || !"checkout.stripe.com".equals(url.getHost()) ||
                    url.getUserInfo()!=null || (url.getPort()!=-1 && url.getPort()!=443))throw new SessionFailure(false);
            } catch(IllegalArgumentException | NullPointerException invalidUrl){throw new SessionFailure(false);}
        }
    }
    private PaymentAttempt locked(String id){
        var owner=em.createQuery("select a.order.id from PaymentAttempt a where a.id=:id",Long.class).setParameter("id",id).getResultList();
        if(owner.isEmpty())throw new SessionFailure(false);
        Orders o=em.find(Orders.class,owner.get(0),LockModeType.PESSIMISTIC_WRITE);em.refresh(o,LockModeType.PESSIMISTIC_WRITE);
        PaymentAttempt a=em.find(PaymentAttempt.class,id,LockModeType.PESSIMISTIC_WRITE);em.refresh(a,LockModeType.PESSIMISTIC_WRITE);return a;
    }
    private void eligible(PaymentAttempt a){
        if(a.getOrder().getPaymentFlow()!=PaymentFlow.STRIPE_TEST || !"IN_ATTESA_PAGAMENTO".equals(a.getOrder().getShipment()) ||
            a.getStatus().terminal() || a.getCloseRequestedAt()!=null || !Boolean.TRUE.equals(a.getOrder().getStockDeducted()))throw new SessionFailure(false);
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
