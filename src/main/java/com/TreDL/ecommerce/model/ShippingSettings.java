package com.TreDL.ecommerce.model;
import jakarta.persistence.*; import java.math.BigDecimal;
@Entity @Table(name="shipping_settings") public class ShippingSettings {
 @Id private Long id=1L;
 @Column(nullable=false,precision=19,scale=2) private BigDecimal cost=new BigDecimal("5.90");
 @Column(name="free_threshold",nullable=false,precision=19,scale=2) private BigDecimal freeThreshold=new BigDecimal("79.00");
 public BigDecimal getCost(){return cost;} public BigDecimal getFreeThreshold(){return freeThreshold;}
 public void setCost(BigDecimal v){cost=Money.validate(v);} public void setFreeThreshold(BigDecimal v){freeThreshold=Money.validate(v);}
}