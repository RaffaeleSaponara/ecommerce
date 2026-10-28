package com.TreDL.ecommerce.dto;

import java.math.BigDecimal;

public record AdminDashboardDTO(long totalOrders, long totalProducts, BigDecimal confirmedOrderValue, String currency) {}
