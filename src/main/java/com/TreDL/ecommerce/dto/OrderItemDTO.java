package com.TreDL.ecommerce.dto;
import com.TreDL.ecommerce.model.OrderItem;
import java.math.BigDecimal;
public record OrderItemDTO(int lineNumber, String productName, BigDecimal unitPrice, int quantity, BigDecimal subtotal) {
 public static OrderItemDTO convertToDTO(OrderItem item) {
  return new OrderItemDTO(item.getLineNumber(), item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getSubtotal());
 }
}
