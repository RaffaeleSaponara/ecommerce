package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.OrderDTO;
import com.TreDL.ecommerce.model.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class OrderManagementService {
    @PersistenceContext private EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private OrderMailEvents orderMail;
    @org.springframework.beans.factory.annotation.Autowired private OrderStockService stock;
    @org.springframework.beans.factory.annotation.Autowired private com.TreDL.ecommerce.repository.PaymentAttemptsRepository payments;

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDTO update(long id, String requested) {
        final OrderStatus next;
        try { next=OrderStatus.valueOf(requested); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Stato richiesto non valido"); }
        Orders order=em.find(Orders.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(order==null) throw new ResponseStatusException(NOT_FOUND,"Ordine non trovato");
        em.refresh(order,LockModeType.PESSIMISTIC_WRITE);
        OrderStatus current=OrderStatus.current(order.getShipment());
        if(order.getPaymentFlow()==PaymentFlow.STRIPE_TEST) {
            var attempt=payments.findByOrderId(id).orElse(null);
            if(attempt==null || attempt.getStatus()!=PaymentStatus.SUCCEEDED)
                throw new ResponseStatusException(CONFLICT,"Ordine Stripe non pagato: operazione amministrativa non consentita");
            if(next==OrderStatus.CONFERMATO || next==OrderStatus.IN_ATTESA_PAGAMENTO || next==OrderStatus.ANNULLATO)
                throw new ResponseStatusException(CONFLICT,"Operazione riservata al dominio pagamenti; annullamento Stripe non ancora disponibile");
        }
        // Retry after a successful write is a no-op, including cancellation.
        if(current==next) return OrderDTO.convertToDTO(order);
        if(!current.allows(next)) throw new ResponseStatusException(CONFLICT,"Transizione di stato non consentita");
        if(next==OrderStatus.ANNULLATO)stock.restore(order);
        order.recordStatusChange(order.getShipment(),next.name());
        order.setShipment(next.name());
        em.flush();
        orderMail.transitioned(order);
        return OrderDTO.convertToDTO(order);
    }
}
