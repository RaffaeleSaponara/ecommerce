package com.TreDL.ecommerce.service;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import com.stripe.net.RequestOptions;
import com.stripe.exception.StripeException;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
class StripeSessionConfiguration {
    @Bean StripeSessionGateway stripeSessionGateway(@Value("${payments.stripe.secret-key:}") String key){
        validateKey(key);
        return new StripeSessionGateway(){
            private RequestOptions options(String idempotency){
                return RequestOptions.builder().setApiKey(key).setIdempotencyKey(idempotency)
                    .setConnectTimeout(5000).setReadTimeout(15000).setMaxNetworkRetries(0).build();
            }
            public Session create(Map<String,Object> params,String idempotency){
                try{return convert(com.stripe.model.checkout.Session.create(params,options(idempotency)));}
                catch(StripeException e){throw controlled(e);}
                catch(RuntimeException e){throw new SessionFailure(false);}
            }
            public Session retrieve(String id){
                try{return convert(com.stripe.model.checkout.Session.retrieve(id,options(null)));}
                catch(StripeException e){throw controlled(e);}
                catch(RuntimeException e){throw new SessionFailure(false);}
            }
            private RuntimeException controlled(StripeException error){
                // Do not propagate provider messages, request bodies or credentials.
                return new SessionFailure(Integer.valueOf(409).equals(error.getStatusCode()) || Integer.valueOf(429).equals(error.getStatusCode()));
            }
            public Session expire(String id){
                try{return convert(com.stripe.model.checkout.Session.retrieve(id,options(null)).expire(Map.of(),options("3dl-expire-"+id)));}
                catch(Exception e){throw new SessionFailure(false);}
            }
            public String paymentState(String id){
                try{var payment=com.stripe.model.PaymentIntent.retrieve(id,options(null));
                    if(!Boolean.FALSE.equals(payment.getLivemode()))throw new SessionFailure(false);
                    return payment.getStatus();
                }catch(Exception e){throw new SessionFailure(false);}
            }
            public String cancelPayment(String id){
                try{var p=com.stripe.model.PaymentIntent.retrieve(id,options(null)).cancel(Map.of(),options("3dl-cancel-"+id));
                    if(!Boolean.FALSE.equals(p.getLivemode()))throw new SessionFailure(false);
                    return p.getStatus();
                }catch(Exception e){throw new SessionFailure(false);}
            }
            private Session convert(com.stripe.model.checkout.Session s){
                if(s.getLivemode()==null || s.getExpiresAt()==null)throw new SessionFailure(false);
                return new Session(s.getId(),s.getPaymentIntent(),s.getAmountTotal()==null?-1:s.getAmountTotal(),
                    s.getCurrency(),Boolean.TRUE.equals(s.getLivemode()),s.getStatus(),s.getPaymentStatus(),
                    s.getUrl(),s.getExpiresAt(),s.getMetadata());
            }
        };
    }
    static void validateKey(String key){
        if(key==null || !(key.startsWith("sk_test_") || key.startsWith("rk_test_")) || key.length()<12)
            throw new IllegalArgumentException("Configurare una chiave server Stripe test");
    }
}
