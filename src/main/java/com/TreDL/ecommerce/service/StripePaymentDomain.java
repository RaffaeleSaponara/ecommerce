package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.PaymentAttemptsRepository;
import jakarta.persistence.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import static org.springframework.http.HttpStatus.*;

/**
 * Internal domain only. No controller, scheduler or production adapter invokes it.
 * Confirm/close require a verified provider outcome supplied by a future adapter.
 * Package visibility intentionally prevents direct controller/admin calls.
 */
@Service
@ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
@Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
class StripePaymentDomain {
    private final CheckoutService checkout;
    private final PaymentAttemptsRepository payments;
    private final OrderStockService stock;
    private final OrderMailEvents mail;
    private final Clock clock;
    @PersistenceContext private EntityManager em;

    StripePaymentDomain(CheckoutService checkout,PaymentAttemptsRepository payments,OrderStockService stock,OrderMailEvents mail,Clock clock) {
        this.checkout=checkout;this.payments=payments;this.stock=stock;this.mail=mail;this.clock=clock;
    }

    OrderDTO reserve(String email,String key,CheckoutRequest request) {
        Orders order=checkout.reserveStripe(email,key,request);
        if(payments.findByOrderId(order.getId()).isEmpty()) {
            if(OrderStatus.current(order.getShipment())!=OrderStatus.IN_ATTESA_PAGAMENTO)
                throw new ResponseStatusException(CONFLICT,"Tentativo mancante: necessaria riconciliazione");
            LocalDateTime created=now();
            payments.saveAndFlush(new PaymentAttempt(order,created,created.plusMinutes(30)));
        }
        return OrderDTO.convertToDTO(order);
    }

    void requestClosure(String attemptId) {
        PaymentAttempt attempt=locked(attemptId);
        if(!attempt.getStatus().terminal())attempt.requestClosure(now());
    }

    OrderDTO confirm(String attemptId,BigDecimal amount,String currency) {
        PaymentAttempt attempt=locked(attemptId);Orders order=attempt.getOrder();
        if(Money.validate(amount).compareTo(attempt.getAmount())!=0 || !attempt.getCurrency().equals(currency)
                || order.getTotal().compareTo(attempt.getAmount())!=0 || !order.getCurrency().equals(currency))
            throw new ResponseStatusException(CONFLICT,"Importo o valuta del pagamento non corrispondenti");
        if(attempt.getStatus()==PaymentStatus.SUCCEEDED)return OrderDTO.convertToDTO(order);
        if(attempt.getStatus().terminal() || OrderStatus.current(order.getShipment())!=OrderStatus.IN_ATTESA_PAGAMENTO
                || !Boolean.TRUE.equals(order.getStockDeducted()))
            throw new ResponseStatusException(CONFLICT,"Pagamento incompatibile con lo stato dell'ordine; necessaria riconciliazione");
        attempt.transition(PaymentStatus.SUCCEEDED,now());
        change(order,OrderStatus.CONFERMATO);
        em.flush();mail.confirmed(order);
        return OrderDTO.convertToDTO(order);
    }

    // A local deadline is never evidence of closure. Only the future provider adapter may call this.
    OrderDTO closeUnpaid(String attemptId,PaymentStatus finalStatus) {
        if(finalStatus!=PaymentStatus.CANCELLED && finalStatus!=PaymentStatus.EXPIRED && finalStatus!=PaymentStatus.FAILED)
            throw new IllegalArgumentException("Chiusura senza pagamento richiesta");
        PaymentAttempt attempt=locked(attemptId);Orders order=attempt.getOrder();
        if(attempt.getStatus()==finalStatus && OrderStatus.current(order.getShipment())==OrderStatus.ANNULLATO)
            return OrderDTO.convertToDTO(order);
        if(attempt.getStatus().terminal() || OrderStatus.current(order.getShipment())!=OrderStatus.IN_ATTESA_PAGAMENTO)
            throw new ResponseStatusException(CONFLICT,"Tentativo già concluso: chiusura non consentita");
        stock.restore(order);attempt.transition(finalStatus,now());
        change(order,OrderStatus.ANNULLATO);
        em.flush();mail.transitioned(order);
        return OrderDTO.convertToDTO(order);
    }

    private PaymentAttempt locked(String id) {
        var owner=em.createQuery("select p.order.id from PaymentAttempt p where p.id=:id",Long.class)
                .setParameter("id",id).getResultList();
        if(owner.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Tentativo non trovato");
        Orders order=em.find(Orders.class,owner.get(0),LockModeType.PESSIMISTIC_WRITE);
        em.refresh(order,LockModeType.PESSIMISTIC_WRITE);
        PaymentAttempt attempt=em.find(PaymentAttempt.class,id,LockModeType.PESSIMISTIC_WRITE);
        em.refresh(attempt,LockModeType.PESSIMISTIC_WRITE);
        if(order.getPaymentFlow()!=PaymentFlow.STRIPE_TEST)throw new ResponseStatusException(CONFLICT,"Ordine legacy");
        return attempt;
    }
    private void change(Orders order,OrderStatus next) {
        OrderStatus current=OrderStatus.current(order.getShipment());
        if(!current.allows(next))throw new ResponseStatusException(CONFLICT,"Transizione ordine non consentita");
        order.recordStatusChange(order.getShipment(),next.name());order.setShipment(next.name());
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
}
