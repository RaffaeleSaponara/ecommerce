package com.TreDL.ecommerce.dto;

import java.math.BigDecimal;
import java.util.List;

public record QuoteDTO(String quoteToken, long expiresAt, String currency,
                       BigDecimal total, List<QuoteLineDTO> items) {}
