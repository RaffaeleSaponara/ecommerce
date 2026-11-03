package com.TreDL.ecommerce.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One full refund per attempt in the initial integration; no refund execution yet. */
@Entity @Table(name="refunds",uniqueConstraints={
    @UniqueConstraint(name="uq_refund_attempt",columnNames="attempt_id"),
    @UniqueConstraint(name="uq_refund_key",columnNames="idempotency_key"),
    @UniqueConstraint(name="uq_refund_provider",columnNames="provider_refund_id")})
public class Refund {
    @Id @Column(length=36) private String id;
    @OneToOne(optional=false,fetch=FetchType.LAZY) @JoinColumn(name="attempt_id",nullable=false) private PaymentAttempt attempt;
    @Column(nullable=false,length=16) private String status;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal amount;
    @Column(nullable=false,columnDefinition="CHAR(3)") private String currency;
    @Column(name="idempotency_key",nullable=false,length=128) private String idempotencyKey;
    @Column(name="provider_refund_id",length=255) private String providerRefundId;
    @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
    @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
    @Column(name="request_json",columnDefinition="TEXT") private String requestJson;
    @Column(name="first_sent_at") private LocalDateTime firstSentAt;
    @Column(name="next_attempt_at") private LocalDateTime nextAttemptAt;
    @Column(name="lease_token",length=36) private String leaseToken;
    @Column(name="lease_until") private LocalDateTime leaseUntil;
    @Column(nullable=false) private int retries;
    @Column(name="provider_status",length=32) private String providerStatus;
    @Column(length=64) private String issue;
    @Column(name="create_blocked",nullable=false) private boolean createBlocked;
    protected Refund() {}
    public RefundStatus getStatus(){return RefundStatus.valueOf(status);}
}
