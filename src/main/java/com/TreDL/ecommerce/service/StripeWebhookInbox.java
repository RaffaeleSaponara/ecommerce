package com.TreDL.ecommerce.service;

import com.stripe.net.Webhook;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.Set;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service @ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
public class StripeWebhookInbox {
    private final JdbcTemplate jdbc;private final String secret;private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();
    public StripeWebhookInbox(JdbcTemplate jdbc,Clock clock,@Value("${payments.stripe.webhook-secret:}") String secret){
        this.jdbc=jdbc;this.clock=clock;this.secret=secret;
    }
    public boolean accept(String body,String signature){
        if(secret==null || !secret.startsWith("whsec_"))throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        if(body==null || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw new ResponseStatusException(BAD_REQUEST);
        JsonNode event;
        try{
            Webhook.constructEvent(body,signature,secret);
            event=json.readTree(body);
            if(!event.has("livemode") || event.get("livemode").asBoolean() || !event.path("id").asText().matches("evt_[A-Za-z0-9_]{1,240}"))throw new IllegalArgumentException();
        }catch(Exception invalid){throw new ResponseStatusException(BAD_REQUEST,"Evento Stripe non valido");}
        String type=event.path("type").asText();
        if(!Set.of("checkout.session.completed","checkout.session.async_payment_succeeded","checkout.session.async_payment_failed","checkout.session.expired").contains(type))return false;
        JsonNode object=event.path("data").path("object"),meta=object.path("metadata");
        String session=object.path("id").asText(),attempt=meta.path("attempt_id").asText();
        Long order=null;try{order=Long.valueOf(meta.path("order_id").asText());}catch(Exception ignored){}
        if(!session.matches("cs_test_[A-Za-z0-9_]{1,240}"))throw new ResponseStatusException(BAD_REQUEST,"Sessione non valida");
        if(!attempt.matches("[a-f0-9-]{36}"))attempt=null;
        try{
            jdbc.update("INSERT INTO payment_events(id,event_type,received_at,session_ref,attempt_hint,order_hint,next_attempt_at,retries) VALUES(?,?,?,?,?,?,?,0)",
                event.path("id").asText(),type,now(),session,attempt,order,now());
        }catch(DuplicateKeyException duplicate){/* Existing durable receipt, never overwrite it. */}
        return true;
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
