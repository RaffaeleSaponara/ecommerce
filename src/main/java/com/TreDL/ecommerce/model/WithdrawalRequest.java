package com.TreDL.ecommerce.model;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="withdrawal_requests",uniqueConstraints=@UniqueConstraint(name="uq_withdrawal_order",columnNames="order_id"))
public class WithdrawalRequest {
 @Id @Column(length=36) public String id;
 @ManyToOne(optional=false) @JoinColumn(name="order_id") @org.hibernate.annotations.OnDelete(action=org.hibernate.annotations.OnDeleteAction.CASCADE) public Orders order;
 @Column(name="recipient_name",nullable=false,length=150) public String name;
 @Column(name="receipt_email",nullable=false,length=254) public String email;
 @Column(name="submitted_at",nullable=false) public LocalDateTime submittedAt;
 @Column(nullable=false,columnDefinition="TEXT") public String declaration;
}
