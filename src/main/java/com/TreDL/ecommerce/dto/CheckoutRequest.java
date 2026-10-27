package com.TreDL.ecommerce.dto;

/** The token identifies the exact displayed quote; confirmation must be explicit. */
public record CheckoutRequest(String quoteToken, boolean confirmPrices) {}
