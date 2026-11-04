package com.TreDL.ecommerce.model;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="email_notifications",uniqueConstraints=@UniqueConstraint(name="uq_email_event",columnNames={"order_id","event_kind"}))
public class EmailNotification {
    @Id @Column(length=36) private String id;
    @ManyToOne(optional=false) @JoinColumn(name="order_id") @org.hibernate.annotations.OnDelete(action=org.hibernate.annotations.OnDeleteAction.CASCADE) private Orders order;
    @Column(name="event_kind",nullable=false,length=20) private String kind;
    @Column(nullable=false,length=254) private String recipient;
    @Column(nullable=false,length=255) private String subject;
    @Column(name="text_body",nullable=false,columnDefinition="TEXT") private String text;
    @Column(name="html_body",nullable=false,columnDefinition="TEXT") private String html;
    @Column(name="contract_text",columnDefinition="TEXT") private String contract;
    @Column(name="created_at",nullable=false) private LocalDateTime created;
    @Column(name="next_attempt_at",nullable=false) private LocalDateTime next;
    @Column(nullable=false) private int attempts;
    @Column(name="lease_token",length=36) private String leaseToken;
    @Column(name="lease_until") private LocalDateTime leaseUntil;
    @Column(name="smtp_accepted_at") private LocalDateTime accepted;
    @Column(name="last_error",length=40) private String error;
}
