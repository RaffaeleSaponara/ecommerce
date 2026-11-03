package com.TreDL.ecommerce.service;

import java.util.Map;

interface StripeSessionGateway {
    record Session(String id,String paymentId,long amount,String currency,boolean live,String status,
                   String paymentStatus,String url,long expiresAt,Map<String,String> metadata) {
        @Override public String toString(){return "Stripe checkout session";}
    }
    Session create(Map<String,Object> parameters,String key);
    Session retrieve(String id);
}
