package com.TreDL.ecommerce.model;

public enum PaymentStatus {
    CREATED, PENDING, SUCCEEDED, FAILED, EXPIRED, CANCELLED;

    public boolean terminal() { return this != CREATED && this != PENDING; }
    public boolean allows(PaymentStatus next) {
        if (this == next) return true;
        return !terminal() && (next == PENDING || next.terminal());
    }
}
