package com.TreDL.ecommerce.model;

public enum RefundStatus {
    REQUESTED, PENDING, SUCCEEDED, FAILED;

    public boolean allows(RefundStatus next) {
        if (this == next) return true;
        return switch (this) {
            case REQUESTED -> next == PENDING || next == SUCCEEDED || next == FAILED;
            case PENDING -> next == SUCCEEDED || next == FAILED;
            case FAILED -> next == PENDING || next == SUCCEEDED;
            case SUCCEEDED -> false;
        };
    }
}
