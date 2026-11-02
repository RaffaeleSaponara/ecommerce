package com.TreDL.ecommerce.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.transaction.annotation.*;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class OrderEmailSender {
    private final ObjectProvider<JavaMailSender> senders;
    private final TemplateEngine templates;
    private final boolean enabled;
    private final String from;
    public OrderEmailSender(ObjectProvider<JavaMailSender> senders,TemplateEngine templates,
            @Value("${app.mail.enabled:false}") boolean enabled,@Value("${app.mail.from:no-reply@3dl.test}") String from) {
        this.senders=senders;this.templates=templates;this.enabled=enabled;this.from=from;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void deliver(OrderMailEvents.Notice notice) {
        if(!enabled)return;
        try {
            JavaMailSender sender=senders.getIfAvailable();
            if(sender==null)throw new IllegalStateException("SMTP non configurato");
            List<String> lines=new ArrayList<>();var order=notice.order();
            String state=switch(notice.kind()){case CONFIRMED->"confermato";case CANCELLED->"annullato";case SHIPPED->"spedito";};
            String subject="3DL — Ordine #"+order.id()+" "+state;
            lines.add("Il tuo ordine #"+order.id()+" è stato "+state+".");
            lines.add("Data: "+DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(notice.occurredAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Europe/Rome")))+" (Europe/Rome)");
            if(notice.kind()==OrderMailEvents.Kind.CONFIRMED) {
                lines.add("Prodotti acquistati:");
                order.items().forEach(item->lines.add(item.productName()+" × "+item.quantity()+" — "+money(item.subtotal())));
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
            var message=sender.createMimeMessage();var helper=new MimeMessageHelper(message,true,"UTF-8");
            helper.setFrom(from);helper.setTo(RegistrationService.normalizeEmail(notice.recipient()));helper.setSubject(subject);
            helper.setText("3DL · Lure Lab\n\n"+String.join("\n",lines),html);sender.send(message);
        } catch(Exception error) {
            // Best effort after commit: no exception/body/recipient is logged or returned to checkout.
            org.slf4j.LoggerFactory.getLogger(OrderEmailSender.class).warn("Invio email ordine non riuscito; verificare SMTP.");
        }
    }
    private String money(BigDecimal amount){return amount.toPlainString().replace('.',',')+" €";}
}
