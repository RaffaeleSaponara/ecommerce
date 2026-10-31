package com.TreDL.ecommerce.dto;
import com.TreDL.ecommerce.model.Orders;
import com.TreDL.ecommerce.model.AddressSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderDTO(Long id, LocalDateTime date, BigDecimal total, String currency, String shipment, List<OrderItemDTO> items, AddressSnapshot shippingAddress, AddressSnapshot billingAddress) {
 public OrderDTO(Long id, LocalDateTime date, BigDecimal total, String currency, String shipment, List<OrderItemDTO> items) { this(id,date,total,currency,shipment,items,null,null); }
 public static OrderDTO convertToDTO(Orders order) {
  return new OrderDTO(order.getId(), order.getDate(), order.getTotal(), order.getCurrency(), order.getShipment(),
   order.getItems().stream().map(OrderItemDTO::convertToDTO).toList(), order.getShippingAddress(), order.getBillingAddress());
 }
}
