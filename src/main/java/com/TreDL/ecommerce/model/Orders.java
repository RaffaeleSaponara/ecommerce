package com.TreDL.ecommerce.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "orders")
public class Orders {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @Column(name = "date")
    private LocalDateTime date;
    @Column(name = "total")
    private double total;
    @Column(name = "ship_status")
    private String shipment;
    @ManyToMany(cascade = CascadeType.PERSIST)
    @JoinTable(
            name = "order_details",
            joinColumns = @JoinColumn(name = "id_order"),
            inverseJoinColumns = @JoinColumn(name = "id_product")
    )
    private List<Products> products;
    @ManyToOne
    @JoinColumn(name = "id_customer")
    private Customers customer;


    public LocalDateTime getDate() {
        return date;
    }

    public void setDate(LocalDateTime date) {
        this.date = date;
    }

    public double getTotal() {
        return total;
    }

    public void setTotal(double total) {
        this.total = total;
    }

    public String getShipment() {
        return shipment;
    }

    public void setShipment(String shipment) {
        this.shipment = shipment;
    }

    public List<Products> getProducts() {
        return products;
    }

    public void setProducts(List<Products> products) {
        this.products = products;
    }

    public Customers getCustomer() {
        return customer;
    }

    public void setCustomer(Customers customer) {
        this.customer = customer;
    }
}
