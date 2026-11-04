package com.TreDL.ecommerce.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class OrderEmailSender {
    private final TemplateEngine templates;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    public OrderEmailSender(TemplateEngine templates) {
        this.templates=templates;
    }
    @org.springframework.context.event.EventListener
    @Transactional(propagation=Propagation.MANDATORY)
    public void deliver(OrderMailEvents.Notice notice) {
            jdbc.queryForObject("SELECT id FROM orders WHERE id=? FOR UPDATE",Long.class,notice.order().id());
            List<String> lines=new ArrayList<>();var order=notice.order();
            String state=switch(notice.kind()){case CONFIRMED->"confermato";case CANCELLED->"annullato";case SHIPPED->"spedito";};
            String subject="3DL — Ordine #"+order.id()+" "+state;
            lines.add("Il tuo ordine #"+order.id()+" è stato "+state+".");
            lines.add("Data: "+DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(notice.occurredAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Europe/Rome")))+" (Europe/Rome)");
            if(notice.kind()==OrderMailEvents.Kind.CONFIRMED) {
                lines.add("Prodotti acquistati:");
                order.items().forEach(item->lines.add(item.productName()+" × "+item.quantity()+" — prezzo unitario: "+money(item.unitPrice())+" — totale riga: "+money(item.subtotal())));
                lines.add("Subtotale prodotti: "+money(order.subtotal()));
                lines.add("Spedizione: "+money(order.shippingCost()));lines.add("Totale: "+money(order.total()));
                var address=order.shippingAddress();lines.add("Indirizzo di spedizione:");
                if(address==null)lines.add("Non disponibile per questo ordine.");
                else {
                    lines.add(address.getRecipientName());lines.add(address.getStreet()+" "+address.getHouseNumber());
                    lines.add(address.getPostalCode()+" "+address.getMunicipality()+" ("+address.getProvince()+") — "+address.getCountry());
                }
                lines.add("Stato: "+order.shipment());
            } else if(notice.kind()==OrderMailEvents.Kind.CANCELLED) {
                lines.add("Le eventuali modalità di rimborso saranno gestite separatamente. Questa email non attesta un rimborso effettuato.");
            } else {
                if(order.carrier()!=null && !order.carrier().isBlank())lines.add("Corriere: "+order.carrier());
                if(order.trackingCode()!=null && !order.trackingCode().isBlank())lines.add("Codice di tracciamento: "+order.trackingCode());
            }
            Context context=new Context(Locale.ITALY);context.setVariable("subject",subject);context.setVariable("lines",lines);
            String html=templates.process("email/order",context);
            String contract=null;
            if(notice.kind()==OrderMailEvents.Kind.CONFIRMED){
                var stored=jdbc.queryForMap("SELECT contract_version,contract_captured_at,contract_text FROM orders WHERE id=?",order.id());
                if(stored.get("contract_text")!=null)
                    contract="Versione: "+stored.get("contract_version")+"\nAcquisita il (UTC): "+stored.get("contract_captured_at")+"\n"+stored.get("contract_text");
                else contract="ORDINE STORICO: condizioni non acquisite all'acquisto. Nessuna attribuzione retroattiva di condizioni.";
            }
            // The order writer already holds the order lock. Uniqueness is also enforced by the database.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM email_notifications WHERE order_id=? AND event_kind=?",Integer.class,order.id(),notice.kind().name())==0)
                jdbc.update("INSERT INTO email_notifications(id,order_id,event_kind,recipient,subject,text_body,html_body,contract_text,created_at,next_attempt_at,attempts) VALUES(?,?,?,?,?,?,?,?,?,?,0)",
                    UUID.randomUUID().toString(),order.id(),notice.kind().name(),RegistrationService.normalizeEmail(notice.recipient()),subject,
                    "3DL · Lure Lab\n\n"+String.join("\n",lines),html,contract,LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS),LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    private String money(BigDecimal amount){return amount.toPlainString().replace('.',',')+" €";}
}
