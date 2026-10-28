package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.repository.OrdersRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class OrderHistoryService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final OrdersRepository orders;

    public OrderHistoryService(OrdersRepository orders) {
        this.orders = orders;
    }

    public record Summary(Long id, String date, BigDecimal total, String currency, String shipment) {}

    @Transactional(readOnly = true)
    public List<Summary> forCustomer(String authenticatedEmail) {
        if (authenticatedEmail == null || authenticatedEmail.isBlank()) {
            throw new IllegalArgumentException("Authentication required");
        }
        return orders.findByCustomerEmailOrderByDateDescIdDesc(authenticatedEmail).stream()
                .map(order -> new Summary(order.getId(),
                        DATE.format(order.getDate().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Europe/Rome"))),
                        order.getTotal(), order.getCurrency(), order.getShipment()))
                .toList();
    }
}
