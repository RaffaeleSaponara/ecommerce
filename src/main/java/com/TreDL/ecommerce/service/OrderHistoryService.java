package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.repository.OrdersRepository;
import com.TreDL.ecommerce.model.AddressSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import com.TreDL.ecommerce.dto.OrderHistoryLine;

@Service
public class OrderHistoryService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final OrdersRepository orders;

    public OrderHistoryService(OrdersRepository orders) {
        this.orders = orders;
    }

    public record Summary(Long id, String date, BigDecimal total, String currency, String shipment, AddressSnapshot shippingAddress, AddressSnapshot billingAddress, List<com.TreDL.ecommerce.dto.OrderDTO.StatusChange> statusHistory, BigDecimal subtotal,BigDecimal shippingCost,String carrier,String trackingCode, List<OrderHistoryLine> items) {
        public Summary(Long id,String date,BigDecimal total,String currency,String shipment,AddressSnapshot shippingAddress,AddressSnapshot billingAddress,List<com.TreDL.ecommerce.dto.OrderDTO.StatusChange> statusHistory){this(id,date,total,currency,shipment,shippingAddress,billingAddress,statusHistory,total,new BigDecimal("0.00"),null,null,List.of());}
        public Summary(Long id,String date,BigDecimal total,String currency,String shipment,AddressSnapshot shippingAddress,AddressSnapshot billingAddress) {this(id,date,total,currency,shipment,shippingAddress,billingAddress,List.of());}
        public Summary(Long id,String date,BigDecimal total,String currency,String shipment) { this(id,date,total,currency,shipment,null,null); }
    }

    @Transactional(readOnly = true)
    public List<Summary> forCustomer(String authenticatedEmail) {
        if (authenticatedEmail == null || authenticatedEmail.isBlank()) {
            throw new IllegalArgumentException("Authentication required");
        }
        var customerOrders = orders.findByCustomerEmailOrderByDateDescIdDesc(authenticatedEmail);
        if (customerOrders.isEmpty()) return List.of();
        var lines = orders.historyLines(authenticatedEmail).stream().collect(Collectors.groupingBy(OrderHistoryLine::orderId));
        return customerOrders.stream()
                .map(order -> new Summary(order.getId(),
                        DATE.format(order.getDate().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Europe/Rome"))),
                        order.getTotal(), order.getCurrency(), order.getShipment(), order.getShippingAddress(), order.getBillingAddress(),
                        order.getStatusHistory().stream().map(change -> new com.TreDL.ecommerce.dto.OrderDTO.StatusChange(change.getChangedAt(),change.getPreviousStatus(),change.getNewStatus())).toList(),order.getSubtotal(),order.getShippingCost(),order.getCarrier(),order.getTrackingCode(),List.copyOf(lines.getOrDefault(order.getId(),List.of()))))
                .toList();
    }
}
