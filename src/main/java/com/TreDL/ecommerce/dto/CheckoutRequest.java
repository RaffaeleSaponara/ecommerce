package com.TreDL.ecommerce.dto;
/** Omitted billing address means the selected shipping address. */
public record CheckoutRequest(String quoteToken, boolean confirmPrices, Long shippingAddressId, Long billingAddressId) {
    public CheckoutRequest(String quoteToken,boolean confirmPrices) { this(quoteToken,confirmPrices,null,null); }
}
