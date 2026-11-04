package com.TreDL.ecommerce.service;
import com.TreDL.ecommerce.model.*;
import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import java.time.*;
import java.util.*;
@Service
public class WithdrawalService {
 @PersistenceContext private EntityManager em;
 private final JdbcTemplate jdbc;
 public WithdrawalService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Choice(Long id,LocalDateTime date,String status) {}
 public record View(String id,Long orderId,String name,String email,LocalDateTime submittedAt,String declaration) {}
 @Transactional(readOnly=true) public List<Choice> choices(String email){
  return em.createQuery("select new com.TreDL.ecommerce.service.WithdrawalService$Choice(o.id,o.date,o.shipment) from Orders o where o.customer.email=:email order by o.date desc",Choice.class).setParameter("email",email).getResultList();
 }
 @Transactional(readOnly=true) public View receipt(String email,String id){
  var rows=em.createQuery("select w from WithdrawalRequest w join fetch w.order o where w.id=:id and o.customer.email=:email",WithdrawalRequest.class).setParameter("id",id).setParameter("email",email).getResultList();
  if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Richiesta non trovata");return view(rows.get(0));
 }
 @Transactional(readOnly=true) public List<View> admin(){return em.createQuery("select w from WithdrawalRequest w join fetch w.order order by w.submittedAt desc",WithdrawalRequest.class).setMaxResults(100).getResultList().stream().map(this::view).toList();}
 @Transactional(isolation=Isolation.READ_COMMITTED) public View submit(String email,long orderId,String name,boolean confirmed){
  if(!confirmed)throw new IllegalArgumentException("Conferma esplicita obbligatoria");
  if(name==null||name.isBlank()||name.strip().length()>150||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Inserisci nome e cognome, massimo 150 caratteri");
  var order=em.find(Orders.class,orderId,LockModeType.PESSIMISTIC_WRITE);
  if(order==null||!order.getCustomer().getEmail().equals(email))throw new ResponseStatusException(NOT_FOUND,"Ordine non trovato");
  var previous=em.createQuery("select w from WithdrawalRequest w where w.order.id=:id",WithdrawalRequest.class).setParameter("id",orderId).getResultList();
  if(!previous.isEmpty())return view(previous.get(0));
  var w=new WithdrawalRequest();w.id=UUID.randomUUID().toString();w.order=order;w.name=name.strip();w.email=order.getCustomer().getEmail();w.submittedAt=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  w.declaration="Io, "+w.name+", comunico di esercitare il diritto di recesso dall'intero contratto relativo all'ordine #"+orderId+".\nRicevuta richiesta tramite account autenticato.\nEmail per la ricevuta: "+w.email+"\nData e ora di trasmissione (UTC): "+w.submittedAt+"\nRiferimento richiesta: "+w.id;
  em.persist(w);em.flush();
  String body="Ricevuta della dichiarazione di recesso\n\n"+w.declaration+"\n\nLa dichiarazione è stata registrata. Questa ricevuta non attesta l'esecuzione di un rimborso né modifica lo stato dell'ordine. I requisiti e gli eventuali termini applicabili saranno verificati separatamente. Contatti e documenti del negozio sono ancora in BOZZA NON DEFINITIVA.";
  jdbc.update("INSERT INTO email_notifications(id,order_id,event_kind,recipient,subject,text_body,html_body,created_at,next_attempt_at,attempts) VALUES(?,?,?,?,?,?,?,?,?,0)",UUID.randomUUID().toString(),orderId,"WITHDRAWAL",w.email,"3DL — Ricevuta recesso ordine #"+orderId,body,"<html lang=\"it\"><body><pre>"+org.springframework.web.util.HtmlUtils.htmlEscape(body)+"</pre></body></html>",w.submittedAt,w.submittedAt);
  return view(w);
 }
 private View view(WithdrawalRequest w){return new View(w.id,w.order.getId(),w.name,w.email,w.submittedAt,w.declaration);}
}
