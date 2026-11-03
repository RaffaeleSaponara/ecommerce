package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.service.StripeRefundOperations;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;

@RestController
@RequestMapping("/api/admin/stripe")
@ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
public class StripeRefundController {
    private final StripeRefundOperations refunds;
    public StripeRefundController(StripeRefundOperations refunds){this.refunds=refunds;}
    public record Confirmation(Boolean confirmed) {}
    @GetMapping("/orders/{id}") public StripeRefundOperations.View view(@PathVariable long id){return refunds.view(id);}
    @GetMapping("/anomalies") public List<StripeRefundOperations.Anomaly> anomalies(){return refunds.anomalies();}
    @GetMapping("/refunds") public List<StripeRefundOperations.RefundSummary> refunds(){return refunds.refunds();}
    @PostMapping("/orders/{id}/cancel-refund") public StripeRefundOperations.View cancel(@PathVariable long id,@RequestBody Confirmation request){return refunds.request(id,true,request!=null && Boolean.TRUE.equals(request.confirmed()));}
    @PostMapping("/orders/{id}/refund") public StripeRefundOperations.View refund(@PathVariable long id,@RequestBody Confirmation request){return refunds.request(id,false,request!=null && Boolean.TRUE.equals(request.confirmed()));}
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String,String>> rejected(org.springframework.web.server.ResponseStatusException error){
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(java.util.Map.of("message",error.getReason()==null?"Operazione non consentita":error.getReason()));
    }
}
