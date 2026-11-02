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

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OrderDTO update(long id, String requested) {
        final OrderStatus next;
        try { next=OrderStatus.valueOf(requested); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Stato richiesto non valido"); }
        Orders order=em.find(Orders.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(order==null) throw new ResponseStatusException(NOT_FOUND,"Ordine non trovato");
        em.refresh(order,LockModeType.PESSIMISTIC_WRITE);
        OrderStatus current=OrderStatus.current(order.getShipment());
        // Retry after a successful write is a no-op, including cancellation.
        if(current==next) return OrderDTO.convertToDTO(order);
        if(!current.allows(next)) throw new ResponseStatusException(CONFLICT,"Transizione di stato non consentita");
        if(next==OrderStatus.ANNULLATO) {
            if(Boolean.TRUE.equals(order.getStockDeducted())) {
                Map<Long,Integer> quantities=new TreeMap<>();
                for(OrderItem item:order.getItems()) {
                    if(item.getProduct()==null) throw new ResponseStatusException(CONFLICT,"Prodotto dell'ordine non più disponibile: annullamento non eseguito");
                    quantities.merge(item.getProduct().getId(),item.getQuantity(),Math::addExact);
                }
                // Same product ordering as checkout prevents product-lock cycles.
                for(var entry:quantities.entrySet()) {
                    Products product=em.find(Products.class,entry.getKey(),LockModeType.PESSIMISTIC_WRITE);
                    if(product==null) throw new ResponseStatusException(CONFLICT,"Prodotto dell'ordine non trovato");
                    em.refresh(product,LockModeType.PESSIMISTIC_WRITE);
                    long restored=(long)product.getStock()+entry.getValue();
                    if(restored>Integer.MAX_VALUE) throw new ResponseStatusException(CONFLICT,"Reintegro superiore alla quantità massima: annullamento non eseguito");
                    product.setStock((int)restored);
                }
                order.setStockDeducted(false);
            }
        }
        order.recordStatusChange(order.getShipment(),next.name());
        order.setShipment(next.name());
        em.flush();
        orderMail.transitioned(order);
        return OrderDTO.convertToDTO(order);
    }
}
