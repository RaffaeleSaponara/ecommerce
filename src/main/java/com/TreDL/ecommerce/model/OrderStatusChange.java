package com.TreDL.ecommerce.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name="order_status_changes")
public class OrderStatusChange {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) @JoinColumn(name="id_order",nullable=false) private Orders order;
    @Column(name="changed_at",nullable=false) private LocalDateTime changedAt;
    @Column(name="previous_status",nullable=false,length=32) private String previousStatus;
    @Column(name="new_status",nullable=false,length=32) private String newStatus;
    protected OrderStatusChange() {}
    public OrderStatusChange(Orders order, String previous, String next) {
        this.order=order; previousStatus=previous; newStatus=next;
        changedAt=LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    public LocalDateTime getChangedAt() {return changedAt;}
    public String getPreviousStatus() {return previousStatus;}
    public String getNewStatus() {return newStatus;}
}
