package com.TreDL.ecommerce.service;

import com.stripe.model.*;
import com.stripe.net.RequestOptions;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

@Configuration
@ConditionalOnProperty(name="payments.stripe.enabled",havingValue="true")
class StripeRefundConfiguration {
    @Bean StripeRefundGateway stripeRefundGateway(@Value("${payments.stripe.secret-key:}") String key){
        StripeSessionConfiguration.validateKey(key);
        return new StripeRefundGateway(){
            RequestOptions options(String idem){
                if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("No provider calls inside transactions");
                return RequestOptions.builder().setApiKey(key).setIdempotencyKey(idem).setConnectTimeout(5000).setReadTimeout(15000).setMaxNetworkRetries(0).build();
            }
            public Capture capture(String id){
                try {
                    var p=PaymentIntent.retrieve(id,options(null));
                    if(!Boolean.FALSE.equals(p.getLivemode()) || p.getLatestCharge()==null)throw new Failure(true);
                    var c=Charge.retrieve(p.getLatestCharge(),options(null));
                    if(!Boolean.FALSE.equals(c.getLivemode()) || !id.equals(c.getPaymentIntent()) || !Objects.equals(p.getAmountReceived(),c.getAmountCaptured()) || !Objects.equals(p.getCurrency(),c.getCurrency()))throw new Failure(true);
                    return new Capture(p.getId(),c.getId(),p.getAmountReceived(),p.getCurrency(),false,"succeeded".equals(p.getStatus()) && Boolean.TRUE.equals(c.getCaptured()) && Boolean.TRUE.equals(c.getPaid()));
                }catch(Failure e){throw e;}catch(Exception e){throw new Failure(false);}
            }
            public List<RemoteRefund> list(String charge){
                try {
                    var items=new ArrayList<RemoteRefund>();
                    var collection=com.stripe.model.Refund.list(Map.of("charge",charge,"limit",100),options(null));
                    for(var r:collection.autoPagingIterable()){if(items.size()>=1000)throw new Failure(true);items.add(convert(r));}
                    return List.copyOf(items);
                }catch(Failure e){throw e;}catch(Exception e){throw new Failure(false);}
            }
            public RemoteRefund create(Map<String,Object> params,String idempotency){
                try{return convert(com.stripe.model.Refund.create(params,options(idempotency)));}
                catch(com.stripe.exception.InvalidRequestException e){throw new Failure(true);}
                catch(Exception e){throw new Failure(false);}
            }
            public RemoteRefund retrieve(String id){
                try{return convert(com.stripe.model.Refund.retrieve(id,options(null)));}
                catch(Exception e){throw new Failure(false);}
            }
            RemoteRefund convert(com.stripe.model.Refund r){return new RemoteRefund(r.getId(),r.getPaymentIntent(),r.getCharge(),r.getAmount(),r.getCurrency(),r.getStatus(),r.getMetadata());}
        };
    }
}
