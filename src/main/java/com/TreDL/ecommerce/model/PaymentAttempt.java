package com.TreDL.ecommerce.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name="payment_attempts", uniqueConstraints={
    @UniqueConstraint(name="uq_payment_order",columnNames="id_order"),
    @UniqueConstraint(name="uq_payment_session",columnNames="provider_session_id"),
    @UniqueConstraint(name="uq_payment_reference",columnNames="provider_payment_id")})
public class PaymentAttempt {
    @Id @Column(length=36) private String id;
    @OneToOne(optional=false,fetch=FetchType.LAZY) @JoinColumn(name="id_order",nullable=false,updatable=false) private Orders order;
    @Column(nullable=false,length=16) private String status;
    @Column(name="expected_amount",nullable=false,precision=19,scale=2,updatable=false) private BigDecimal amount;
    @Column(nullable=false,columnDefinition="CHAR(3)",updatable=false) private String currency;
    @Column(name="provider_session_id",length=255) private String sessionId;
    @Column(name="provider_payment_id",length=255) private String paymentId;
    @Column(name="created_at",nullable=false,updatable=false) private LocalDateTime createdAt;
    @Column(name="expires_at",nullable=false) private LocalDateTime expiresAt;
    @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
    @Column(name="close_requested_at") private LocalDateTime closeRequestedAt;
    @Column(name="next_reconcile_at") private LocalDateTime nextReconcileAt;

    protected PaymentAttempt() {}
    public PaymentAttempt(Orders order,LocalDateTime now,LocalDateTime expiresAt) {
        if(order.getPaymentFlow()!=PaymentFlow.STRIPE_TEST) throw new IllegalArgumentException("Stripe order required");
        if(!expiresAt.isAfter(now)) throw new IllegalArgumentException("Invalid expiration");
        this.id=UUID.randomUUID().toString();this.order=order;this.amount=Money.validate(order.getTotal());
        this.currency=order.getCurrency();this.status=PaymentStatus.CREATED.name();
        this.createdAt=now;this.updatedAt=now;this.expiresAt=expiresAt;this.nextReconcileAt=expiresAt;
    }
    public String getId(){return id;}
    public Orders getOrder(){return order;}
    public PaymentStatus getStatus(){return PaymentStatus.valueOf(status);}
    public BigDecimal getAmount(){return amount;}
    public String getCurrency(){return currency;}
    public LocalDateTime getExpiresAt(){return expiresAt;}
    public LocalDateTime getCloseRequestedAt(){return closeRequestedAt;}
    public void requestClosure(LocalDateTime now){if(closeRequestedAt==null)closeRequestedAt=now;updatedAt=now;}
    public void transition(PaymentStatus next,LocalDateTime now){
        if(!getStatus().allows(Objects.requireNonNull(next)))throw new IllegalStateException("Payment transition not allowed");
        status=next.name();updatedAt=now;if(next.terminal())nextReconcileAt=null;
    }
}
