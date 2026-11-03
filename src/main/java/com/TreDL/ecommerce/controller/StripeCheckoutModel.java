package com.TreDL.ecommerce.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice
public class StripeCheckoutModel {
    @Value("${payments.stripe.enabled:false}") private boolean stripe;
    @Value("${payments.stripe.customer-checkout.enabled:false}") private boolean customer;
    @ModelAttribute("stripeCustomerCheckoutEnabled")
    public boolean enabled(){return stripe && customer;}
}
