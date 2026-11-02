package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.OrderDTO;
import com.TreDL.ecommerce.model.Orders;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class OrderMailEvents {
    public enum Kind { CONFIRMED, CANCELLED, SHIPPED }
    public record Notice(Kind kind,String recipient,OrderDTO order,LocalDateTime occurredAt) {
        @Override public String toString(){return "Order mail notice";}
    }
    private final ApplicationEventPublisher events;
    public OrderMailEvents(ApplicationEventPublisher events){this.events=events;}
    public void confirmed(Orders order){publish(Kind.CONFIRMED,order,order.getDate());}
    public void transitioned(Orders order) {
        Kind kind=switch(order.getShipment()){case "ANNULLATO"->Kind.CANCELLED;case "SPEDITO"->Kind.SHIPPED;default->null;};
        if(kind!=null) {
            var history=order.getStatusHistory();
            publish(kind,order,history.get(history.size()-1).getChangedAt());
        }
    }
    private void publish(Kind kind,Orders order,LocalDateTime date) {
        events.publishEvent(new Notice(kind,order.getCustomer().getEmail(),OrderDTO.convertToDTO(order),date));
    }
}
