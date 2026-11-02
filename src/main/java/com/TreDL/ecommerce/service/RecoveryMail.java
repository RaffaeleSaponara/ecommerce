package com.TreDL.ecommerce.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import java.net.URI;

@Service
public class RecoveryMail {
    private final ObjectProvider<JavaMailSender> senders;
    private final boolean enabled;
    private final String from,publicUrl;
    public RecoveryMail(ObjectProvider<JavaMailSender> senders,@Value("${app.mail.enabled:false}") boolean enabled,
            @Value("${app.mail.from:no-reply@3dl.test}") String from,@Value("${app.mail.public-url:http://127.0.0.1:9085}") String publicUrl) {
        this.senders=senders;this.enabled=enabled;this.from=from;this.publicUrl=publicUrl.replaceAll("/+$","");
        URI uri=URI.create(this.publicUrl);
        if(uri.getScheme()==null || !java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Configurare un URL pubblico valido per il recupero password");
    }
    public boolean enabled(){return enabled;}
    public void recovery(String email,String token) {
        send(email,"Recupero password — Lure Lab","Per impostare una nuova password apri questo link entro 30 minuti:\n\n"
            +publicUrl+"/reset-password?token="+token+"\n\nSolo il link più recente è valido. Se non hai richiesto il recupero, ignora questa email.");
    }
    public void changed(String email) {
        send(email,"Password aggiornata — Lure Lab","La password del tuo account Lure Lab è stata aggiornata.\nSe non sei stato tu, contattaci. Questa email non contiene credenziali.");
    }
    private void send(String email,String subject,String body) {
        if(!enabled)return;
        try {
            JavaMailSender sender=senders.getIfAvailable();
            if(sender==null)throw new MailSendException("SMTP non configurato");
            SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);message.setSubject(subject);message.setText(body);
            sender.send(message);
        } catch(MailException error) {
            // Do not log the exception: transport errors may contain recipients, body or tokens.
            org.slf4j.LoggerFactory.getLogger(RecoveryMail.class).warn("Invio email account non riuscito; verificare la configurazione SMTP.");
        }
    }
}
