package com.TreDL.ecommerce.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Storage prepared for a future authenticated provider adapter; no event ingestion yet. */
@Entity @Table(name="payment_events")
public class PaymentEvent {
    @Column(name="session_ref",length=255) private String sessionRef;
    @Column(name="attempt_hint",length=36) private String attemptHint;
    @Column(name="order_hint") private Long orderHint;
    @Column(name="next_attempt_at") private LocalDateTime nextAttemptAt;
    @Column(name="lease_token",length=36) private String leaseToken;
    @Column(name="lease_until") private LocalDateTime leaseUntil;
    @Column(nullable=false) private int retries;
    @Id @Column(length=255) private String id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="attempt_id") private PaymentAttempt attempt;
    @Column(name="event_type",nullable=false,length=100) private String eventType;
    @Column(name="received_at",nullable=false) private LocalDateTime receivedAt;
    @Column(name="processed_at") private LocalDateTime processedAt;
    @Column(length=32) private String outcome;
    @Column(name="resolved_at") private LocalDateTime resolvedAt;
    protected PaymentEvent() {}
}
