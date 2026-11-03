package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.service.StripeWebhookInbox;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;

@RestController @ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
public class StripeWebhookController {
    private final StripeWebhookInbox inbox;
    public StripeWebhookController(StripeWebhookInbox inbox){this.inbox=inbox;}
    @PostMapping(value="/api/stripe/webhook",consumes="application/json")
    public ResponseEntity<Void> receive(jakarta.servlet.http.HttpServletRequest request,@RequestHeader(value="Stripe-Signature",required=false) String signature) throws java.io.IOException {
        byte[] bytes=request.getInputStream().readNBytes(262145);
        if(bytes.length>262144)return ResponseEntity.status(413).build();
        boolean stored=inbox.accept(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),signature);
        return stored?ResponseEntity.ok().build():ResponseEntity.noContent().build();
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).build();}
}
