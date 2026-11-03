package com.TreDL.ecommerce.dto;

import java.math.BigDecimal;
import com.TreDL.ecommerce.model.Money;

/** Values from the persisted order, never from the current catalog. */
public record OrderHistoryLine(Long orderId, String productName, int quantity, BigDecimal unitPrice) {
    public BigDecimal subtotal() { return Money.subtotal(unitPrice, quantity); }
}
