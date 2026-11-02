package com.TreDL.ecommerce.dto;
import com.TreDL.ecommerce.model.Orders;
import com.TreDL.ecommerce.model.AddressSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderDTO(Long id, LocalDateTime date, BigDecimal total, String currency, String shipment, List<OrderItemDTO> items, AddressSnapshot shippingAddress, AddressSnapshot billingAddress, List<StatusChange> statusHistory, BigDecimal subtotal, BigDecimal shippingCost, String carrier, String trackingCode) {
 public OrderDTO(Long id,LocalDateTime date,BigDecimal total,String currency,String shipment,List<OrderItemDTO> items,AddressSnapshot shippingAddress,AddressSnapshot billingAddress,List<StatusChange> statusHistory){this(id,date,total,currency,shipment,items,shippingAddress,billingAddress,statusHistory,total,new BigDecimal("0.00"),null,null);}
 public record StatusChange(LocalDateTime date, String previousStatus, String newStatus) {}
 public OrderDTO(Long id, LocalDateTime date, BigDecimal total, String currency, String shipment, List<OrderItemDTO> items, AddressSnapshot shippingAddress, AddressSnapshot billingAddress) { this(id,date,total,currency,shipment,items,shippingAddress,billingAddress,List.of()); }
 public OrderDTO(Long id, LocalDateTime date, BigDecimal total, String currency, String shipment, List<OrderItemDTO> items) { this(id,date,total,currency,shipment,items,null,null); }
 public static OrderDTO convertToDTO(Orders order) {
  return new OrderDTO(order.getId(), order.getDate(), order.getTotal(), order.getCurrency(), order.getShipment(),
   order.getItems().stream().map(OrderItemDTO::convertToDTO).toList(), order.getShippingAddress(), order.getBillingAddress(),
   order.getStatusHistory().stream().map(change -> new StatusChange(change.getChangedAt(),change.getPreviousStatus(),change.getNewStatus())).toList(),order.getSubtotal(),order.getShippingCost(),order.getCarrier(),order.getTrackingCode());
 }
}
