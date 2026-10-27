package com.TreDL.ecommerce.dto;

import com.TreDL.ecommerce.model.Orders;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDTO(LocalDateTime date, double total, String shipment, List<ProductDTO> products) {
    public static OrderDTO convertToDTO(Orders order) {
        return new OrderDTO(order.getDate(), order.getTotal(), order.getShipment(),
                order.getProducts().stream().map(ProductDTO::convertToDTO).toList());
    }
}
