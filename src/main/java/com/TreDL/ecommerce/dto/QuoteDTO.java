package com.TreDL.ecommerce.dto;

import java.math.BigDecimal;
import java.util.List;

public record QuoteDTO(String quoteToken, long expiresAt, String currency,
                       BigDecimal total, List<QuoteLineDTO> items,BigDecimal subtotal,BigDecimal shippingCost) {
 public QuoteDTO(String token,long expiry,String currency,BigDecimal total,List<QuoteLineDTO> items){this(token,expiry,currency,total,items,total,new BigDecimal("0.00"));}
}
