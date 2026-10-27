package com.TreDL.ecommerce.dto;

import java.math.BigDecimal;

public record QuoteLineDTO(Long productId, String productName, BigDecimal unitPrice,
                           int quantity, BigDecimal subtotal) {}
