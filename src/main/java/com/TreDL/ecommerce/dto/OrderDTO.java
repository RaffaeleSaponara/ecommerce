package com.TreDL.ecommerce.dto;
import com.TreDL.ecommerce.model.Orders;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record OrderDTO(Long id, LocalDateTime date, BigDecimal total, String currency, String shipment, List<OrderItemDTO> items) {
 public static OrderDTO convertToDTO(Orders order) {
  return new OrderDTO(order.getId(), order.getDate(), order.getTotal(), order.getCurrency(), order.getShipment(),
   order.getItems().stream().map(OrderItemDTO::convertToDTO).toList());
 }
}
