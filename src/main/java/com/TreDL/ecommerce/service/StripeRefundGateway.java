package com.TreDL.ecommerce.service;
import java.util.*;
interface StripeRefundGateway {
    record Capture(String payment,String charge,long amount,String currency,boolean live,boolean succeeded) {
        @Override public String toString(){return "Stripe capture";}
    }
    record RemoteRefund(String id,String payment,String charge,long amount,String currency,String status,Map<String,String> metadata) {
        @Override public String toString(){return "Stripe refund";}
    }
    Capture capture(String payment);
    List<RemoteRefund> list(String charge);
    RemoteRefund create(Map<String,Object> params,String key);
    RemoteRefund retrieve(String id);
    class Failure extends RuntimeException {
        final boolean definitive;
        Failure(boolean definitive){super("Operazione Stripe non disponibile");this.definitive=definitive;}
    }
}
