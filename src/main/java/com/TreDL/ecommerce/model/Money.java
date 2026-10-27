package com.TreDL.ecommerce.model;
import java.math.*;
import java.util.Objects;
/** Exact EUR amounts representable by DECIMAL(19,2). */
public final class Money {
    private Money() {}
    public static BigDecimal validate(BigDecimal value) {
        Objects.requireNonNull(value, "Amount is required");
        final BigDecimal amount;
        try { amount = value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Fraction of a cent", e); }
        if (amount.signum() < 0 || amount.precision() > 19) throw new IllegalArgumentException("Amount outside DECIMAL(19,2)");
        return amount;
    }
    public static BigDecimal subtotal(BigDecimal price, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        return validate(validate(price).multiply(BigDecimal.valueOf(quantity)));
    }
}
