package com.TreDL.ecommerce.service;
import com.TreDL.ecommerce.model.*; import com.TreDL.ecommerce.dto.OrderDTO; import jakarta.persistence.*;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException; import java.math.BigDecimal; import static org.springframework.http.HttpStatus.*;
@Service public class ShippingService {
 @PersistenceContext private EntityManager em;
 public record Settings(BigDecimal cost,BigDecimal freeThreshold) {}
 @Transactional public void initialize(){if(em.find(ShippingSettings.class,1L)==null)em.persist(new ShippingSettings());}
 @Transactional public Settings settings(){return dto(locked());}
 public ShippingSettings locked(){ShippingSettings s=em.find(ShippingSettings.class,1L,LockModeType.PESSIMISTIC_READ);if(s==null)throw new IllegalStateException("Tariffe spedizione non inizializzate");em.refresh(s,LockModeType.PESSIMISTIC_READ);return s;}
 public BigDecimal cost(ShippingSettings s,BigDecimal subtotal){return subtotal.compareTo(s.getFreeThreshold())>=0?new BigDecimal("0.00"):s.getCost();}
 @Transactional public Settings update(Settings input){
  if(input==null||input.cost()==null||input.freeThreshold()==null)throw new IllegalArgumentException("Costo e soglia sono obbligatori");
  BigDecimal cost=Money.validate(input.cost()),threshold=Money.validate(input.freeThreshold());
  ShippingSettings s=em.find(ShippingSettings.class,1L,LockModeType.PESSIMISTIC_WRITE);em.refresh(s,LockModeType.PESSIMISTIC_WRITE);
  s.setCost(cost);s.setFreeThreshold(threshold);return dto(s);
 }
 public record Tracking(String carrier,String trackingCode) {}
 @Transactional public OrderDTO tracking(long id,Tracking input){
  if(input==null)throw new IllegalArgumentException("Tracking richiesto");
  String carrier=text(input.carrier(),100),code=text(input.trackingCode(),150);
  Orders order=em.find(Orders.class,id,LockModeType.PESSIMISTIC_WRITE);
  if(order==null)throw new ResponseStatusException(NOT_FOUND,"Ordine non trovato");em.refresh(order,LockModeType.PESSIMISTIC_WRITE);
  order.setCarrier(carrier);order.setTrackingCode(code);return OrderDTO.convertToDTO(order);
 }
 private String text(String value,int limit){if(value==null||value.isBlank())return null;String s=value.strip();if(s.length()>limit||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Tracking non valido o troppo lungo");return s;}
 private Settings dto(ShippingSettings s){return new Settings(s.getCost(),s.getFreeThreshold());}
}