package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.dto.CheckoutRequest;
import com.TreDL.ecommerce.service.StripeCustomerCheckout;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.security.Principal;
import java.net.URI;

@Controller
public class StripeCustomerController {
    private final StripeCustomerCheckout checkout;
    public StripeCustomerController(StripeCustomerCheckout checkout){this.checkout=checkout;}
    @PostMapping(value="/cart/stripe/checkout",consumes="application/json") @ResponseBody
    public StripeCustomerCheckout.Result start(Principal principal,@RequestHeader("Idempotency-Key") String key,@RequestBody CheckoutRequest request){
        return checkout.start(principal.getName(),key,request);
    }
    @PostMapping("/user/orders/{id}/payment/resume")
    public ResponseEntity<Void> resume(Principal principal,@PathVariable long id){
        var result=checkout.resume(principal.getName(),id);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(result.redirectUrl()==null?
            "/user/orders/"+id+"/payment":result.redirectUrl())).build();
    }
    @GetMapping("/user/orders/{id}/payment/state") @ResponseBody
    public StripeCustomerCheckout.State state(Principal principal,@PathVariable long id){return checkout.state(principal.getName(),id);}
    @GetMapping({"/user/orders/{id}/payment","/user/orders/{id}/payment/success","/user/orders/{id}/payment/cancel"})
    public String page(Principal principal,@PathVariable long id,Model model){
        model.addAttribute("payment",checkout.state(principal.getName(),id));return "stripe-payment";
    }
}
