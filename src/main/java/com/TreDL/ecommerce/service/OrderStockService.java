package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.springframework.http.HttpStatus.CONFLICT;

/** Caller must hold the order write lock. Products always lock in ascending ID order. */
@Service
class OrderStockService {
    @PersistenceContext private EntityManager em;

    @Transactional(propagation=Propagation.MANDATORY)
    void restore(Orders order) {
        if(!Boolean.TRUE.equals(order.getStockDeducted())) return;
        Map<Long,Integer> quantities=new TreeMap<>();
        for(OrderItem item:order.getItems()) {
            if(item.getProduct()==null)throw new ResponseStatusException(CONFLICT,"Prodotto dell'ordine non più disponibile: annullamento non eseguito");
            quantities.merge(item.getProduct().getId(),item.getQuantity(),Math::addExact);
        }
        for(var entry:quantities.entrySet()) {
            Products product=em.find(Products.class,entry.getKey(),LockModeType.PESSIMISTIC_WRITE);
            if(product==null)throw new ResponseStatusException(CONFLICT,"Prodotto dell'ordine non trovato");
            em.refresh(product,LockModeType.PESSIMISTIC_WRITE);
            long restored=(long)product.getStock()+entry.getValue();
            if(restored>Integer.MAX_VALUE)throw new ResponseStatusException(CONFLICT,"Reintegro superiore alla quantità massima: annullamento non eseguito");
            product.setStock((int)restored);
        }
        order.setStockDeducted(false);
    }
}
