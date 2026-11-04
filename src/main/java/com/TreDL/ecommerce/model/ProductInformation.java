package com.TreDL.ecommerce.model;
import jakarta.persistence.*;
@Entity @Table(name="product_information")
public class ProductInformation {
 @Id @Column(name="product_id") public Long productId;
 @OneToOne @MapsId @JoinColumn(name="product_id") @org.hibernate.annotations.OnDelete(action=org.hibernate.annotations.OnDeleteAction.CASCADE) public Products product;
 @Column(name="model_code",length=80) public String modelCode;
 @Column(name="identification",length=255) public String identification;
 @Column(name="materials",length=1000) public String materials;
 @Column(name="dimensions",length=255) public String dimensions;
 @Column(name="manufacturer_name",length=150) public String manufacturerName;
 @Column(name="manufacturer_address",length=500) public String manufacturerAddress;
 @Column(name="manufacturer_email",length=254) public String manufacturerEmail;
 @Column(name="warnings",length=2000) public String warnings;
 @Column(name="instructions",length=2000) public String instructions;
 @Column(name="weight_grams",precision=9,scale=3) public java.math.BigDecimal weightGrams;
 @Column(nullable=false) public boolean verified;
}
