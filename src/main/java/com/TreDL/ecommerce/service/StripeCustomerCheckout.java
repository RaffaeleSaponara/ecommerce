package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.CheckoutRequest;
import com.TreDL.ecommerce.model.*;
import jakarta.persistence.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import static org.springframework.http.HttpStatus.*;

/** Customer orchestration only; provider calls follow committed local transactions. */
@Service("stripeCustomerCheckout")
public class StripeCustomerCheckout {
    @PersistenceContext private EntityManager em;
    private final ObjectProvider<StripePaymentDomain> domain;
    private final ObjectProvider<StripeCheckoutSessions> sessions;
    private final TransactionTemplate read;
    private final Clock clock;
    private final boolean enabled;
    public StripeCustomerCheckout(ObjectProvider<StripePaymentDomain> domain,ObjectProvider<StripeCheckoutSessions> sessions,
            PlatformTransactionManager manager,Clock clock,
            @Value("${payments.stripe.enabled:false}") boolean stripe,
            @Value("${payments.stripe.customer-checkout.enabled:false}") boolean customer) {
        this.domain=domain;this.sessions=sessions;this.clock=clock;enabled=stripe && customer;
        read=new TransactionTemplate(manager);read.setReadOnly(true);
    }
    public boolean available(){return enabled;}
    public record State(long orderId,String orderStatus,String paymentStatus,boolean resumable,String message) {}
    public record Result(State state,String redirectUrl) {}
    private record Owned(State state,String attempt) {}
    private void gate(){
        if(!enabled)throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Checkout Stripe non disponibile");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Customer checkout requires no ambient transaction");
    }
    public Result start(String email,String key,CheckoutRequest request){
        gate();
        var order=domain.getObject().reserve(email,key,request);
        return resume(email,order.id());
    }
    public State state(String email,long orderId){gate();return owned(email,orderId).state();}
    public Result resume(String email,long orderId){
        gate();var owner=owned(email,orderId);
        if(!owner.state().resumable())return new Result(owner.state(),null);
        try {
            var remote=sessions.getObject().obtain(owner.attempt());
            var state=owned(email,orderId).state();
            return new Result(state,state.resumable()?remote.url():null);
        } catch(RuntimeException failure) {
            // The reservation may already be committed. Never retry with another order/key.
            // Do not expose provider/database details or log payment data.
            var state=owned(email,orderId).state();
            return new Result(new State(state.orderId(),state.orderStatus(),state.paymentStatus(),state.resumable(),
                "Pagamento non disponibile o in verifica. Consulta questo ordine e riprova senza crearne un altro."),null);
        }
    }
    private Owned owned(String email,long id){
        return read.execute(tx->{
            var found=em.createQuery("select a from PaymentAttempt a join fetch a.order o join o.customer c where o.id=:id and c.email=:email",PaymentAttempt.class)
                .setParameter("id",id).setParameter("email",email).getResultList();
            if(found.isEmpty() || found.get(0).getOrder().getPaymentFlow()!=PaymentFlow.STRIPE_TEST)
                throw new ResponseStatusException(NOT_FOUND,"Ordine non trovato");
            var a=found.get(0);var o=a.getOrder();
            boolean active=!a.getStatus().terminal() && "IN_ATTESA_PAGAMENTO".equals(o.getShipment())
                && a.getCloseRequestedAt()==null && a.getExpiresAt().isAfter(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
            String message=a.getStatus()==PaymentStatus.SUCCEEDED?"Pagamento confermato.":a.getStatus().terminal()?"Tentativo concluso. Non è possibile riprendere il pagamento.":
                active?"Ordine in attesa di pagamento. Il ritorno dal pagamento non costituisce una conferma.":"Pagamento scaduto o in verifica. Attendi l’aggiornamento dello stato dell’ordine.";
            return new Owned(new State(id,o.getShipment(),a.getStatus().name(),active,message),a.getId());
        });
    }
}
