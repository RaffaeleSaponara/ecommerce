package com.TreDL.ecommerce.model;
import jakarta.persistence.*;
@Entity @Table(name="customer_addresses")
public class CustomerAddress {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false, fetch=FetchType.LAZY) @JoinColumn(name="id_customer", nullable=false) private Customers customer;
    @Embedded private AddressSnapshot address;
    @Column(name="default_address", nullable=false) private boolean defaultAddress;
    public Long getId() { return id; }
    public Customers getCustomer() { return customer; }
    public void setCustomer(Customers customer) { this.customer=customer; }
    public AddressSnapshot getAddress() { return address; }
    public void setAddress(AddressSnapshot address) { this.address=address; }
    public boolean isDefaultAddress() { return defaultAddress; }
    public void setDefaultAddress(boolean value) { defaultAddress=value; }
}
